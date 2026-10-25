// Настраивает контроллер CI банка при каждом старте. Скрипт идемпотентен и закрывается при сбое:
// аутентификация и авторизация ставятся раньше всего, что может упасть, поэтому ошибка позже
// оставляет контроллер, которым никто не может пользоваться, а не открытый.
// Настройки берутся из окружения контейнера; пароль администратора читается из смонтированного файла
// и не попадает в журнал.
import hudson.model.Node
import hudson.model.User
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.csrf.DefaultCrumbIssuer
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource
import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration

def setting(String name) {
    def value = System.getenv(name)
    if (!value) {
        throw new IllegalStateException("Missing controller setting " + name)
    }
    return value
}

def instance = Jenkins.get()

// Сначала безопасность: только локальные учётные записи, без самостоятельной регистрации и анонимного чтения.
def realm = new HudsonPrivateSecurityRealm(false)
instance.setSecurityRealm(realm)
def authorization = new FullControlOnceLoggedInAuthorizationStrategy()
authorization.setAllowAnonymousRead(false)
instance.setAuthorizationStrategy(authorization)
// Защита от CSRF остаётся включённой; адрес клиента исключён из crumb, потому что каждый запрос приходит
// через проброс порта контейнера.
instance.setCrumbIssuer(new DefaultCrumbIssuer(true))

// Контроллер только планирует: каждая сборка идёт на выделенном агенте.
instance.setNumExecutors(0)
instance.setMode(Node.Mode.EXCLUSIVE)
// Агенты подключаются по WebSocket через HTTPS-слушатель, поэтому входящий TCP-порт агентов остаётся закрытым.
instance.setSlaveAgentPort(-1)
instance.setNoUsageStatistics(true)
JenkinsLocationConfiguration.get().setUrl(setting("BANK_JENKINS_ROOT_URL"))

def adminName = setting("BANK_JENKINS_ADMIN")
def adminPassword = new File("/run/bank-jenkins/admin_password").text.trim()
if (adminPassword.length() < 32) {
    throw new IllegalStateException("The admin password file is missing or too short")
}
def admin = User.getById(adminName, false)
if (admin == null) {
    realm.createAccount(adminName, adminPassword)
} else {
    // Ротация внешнего файла вступает в силу при следующем старте.
    admin.addProperty(HudsonPrivateSecurityRealm.Details.fromPlainPassword(adminPassword))
}

def agentName = setting("BANK_JENKINS_AGENT_NAME")
def launcher = new JNLPLauncher()
launcher.setWebSocket(true)
def agent = new DumbSlave(agentName, setting("BANK_JENKINS_AGENT_ROOT"), launcher)
agent.setNodeDescription("Dedicated bank Kubernetes agent with its own Docker daemon")
agent.setNumExecutors(1)
agent.setMode(Node.Mode.EXCLUSIVE)
agent.setLabelString(agentName)
agent.setRetentionStrategy(new RetentionStrategy.Always())
if (instance.getNode(agentName) == null) {
    instance.addNode(agent)
} else {
    instance.updateNode(agent)
}

def escaped = { String name ->
    setting(name).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
def jobXml = """<?xml version='1.1' encoding='UTF-8'?>
<flow-definition>
  <description>Bank pipeline from the local source snapshot</description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition">
    <scm class="hudson.plugins.git.GitSCM">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>${escaped("BANK_JENKINS_SCM_URL")}</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>${escaped("BANK_JENKINS_SCM_BRANCH")}</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions/>
    </scm>
    <scriptPath>${escaped("BANK_JENKINS_SCRIPT_PATH")}</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>"""
def jobName = setting("BANK_JENKINS_JOB")
def job = instance.getItem(jobName)
if (job == null) {
    instance.createProjectFromXML(jobName, new ByteArrayInputStream(jobXml.getBytes("UTF-8")))
} else {
    job.updateByXml((Source) new StreamSource(new StringReader(jobXml)))
}

instance.save()
println("Bank CI controller configured: agent " + agentName + ", job " + jobName)
