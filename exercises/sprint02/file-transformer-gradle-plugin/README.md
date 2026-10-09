# Преобразование файлов: Gradle-плагин

Плагин `dev.principalwater.file-transformer` создаёт `transformFiles` и `countWords`. Java 21, Gradle 8.14.3; версия wrapper закреплена вместе с SHA256 официального дистрибутива.

В extension `fileTransformer` настраиваются `inputDir`, `outputDir`, `fileType`, `transformType`. По умолчанию: `input`, `build/transformed`, `.txt`, `uppercase`. Допустима также трансформация `lowercase`; `fileType` — одно расширение, например `.txt` или `.xml`, без масок. Обрабатываются файлы непосредственно во входном каталоге, в UTF-8; преобразование регистра использует `Locale.ROOT`.

Входной каталог должен существовать, выходной создаётся при необходимости. Пересечение каталогов запрещено, включая разрешённые символические ссылки. Неизвестная трансформация, некорректное расширение и ошибки файловой системы завершают сборку с объяснением. Запись через временный файл и замену не перезаписывает цель существующей ссылки; атомарная замена зависит от файловой системы.

`countWords` получает объявленные outputs `transformFiles`: Gradle запускает преобразование раньше подсчёта. Слово — последовательность непробельных символов с Unicode whitespace. Чужие и оставшиеся от прежних входов файлы не учитываются; выходной каталог целиком не очищается. Отслеживается содержимое всего входного каталога, поэтому изменение неподходящего по расширению файла тоже может вызвать повторное выполнение.

## Сборка и пример

Откройте корневой `build.gradle.kts` как Gradle-проект в IDE с JDK 21. Из каталога плагина:

```sh
./gradlew test validatePlugins
./gradlew -p consumer countWords
```

`consumer/settings.gradle.kts` подключает плагин через `pluginManagement { includeBuild("..") }`; удалённая публикация не нужна. В `consumer/build.gradle.kts` задано:

```kotlin
plugins { id("dev.principalwater.file-transformer") }
fileTransformer {
    inputDir.set(layout.projectDirectory.dir("input"))
    outputDir.set(layout.buildDirectory.dir("processed"))
    fileType.set(".txt")
    transformType.set("uppercase")
}
```

Пример преобразует `Hello Java / Привет Gradle` в верхний регистр и выводит `Word count: 4`. Результат — `consumer/build/processed/message.txt`. Повторный запуск без изменений оставляет `transformFiles` в состоянии `UP-TO-DATE`; подсчёт снова выводит число слов.

Семь запусков TestKit прошли: uppercase/lowercase с фильтрацией и подсчётом, повторное выполнение/удаление входа, отказ от опасного пересечения каталогов с сохранением оригинала, неверная трансформация/расширение и отсутствующий входной каталог. `validatePlugins` и отдельный consumer через wrapper прошли. Проверен Gradle 8.14.3 на JDK 21; другие Gradle/JDK и configuration cache здесь не заявлены.

Источники: [binary plugins](https://docs.gradle.org/current/userguide/implementing_gradle_plugins_binary.html), [task inputs/outputs](https://docs.gradle.org/current/userguide/implementing_custom_tasks.html), [TestKit](https://docs.gradle.org/current/userguide/test_kit.html).

Укажите JDK 21 в `JAVA_HOME`. Wrapper сохранён с [исходной лицензией](gradle/wrapper/LICENSE) и [NOTICE](gradle/wrapper/NOTICE); эти условия относятся к компонентам Gradle.
