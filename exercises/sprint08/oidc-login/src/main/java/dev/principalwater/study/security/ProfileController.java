package dev.principalwater.study.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ProfileController {
    @GetMapping("/")
    String home() {
        return "home";
    }

    @GetMapping("/profile")
    String profile(@AuthenticationPrincipal OidcUser user, Model model) {
        model.addAttribute("profile", identity(user));
        return "profile";
    }

    @GetMapping("/api/profile")
    @ResponseBody
    Profile identity(@AuthenticationPrincipal OidcUser user) {
        return new Profile(user.getSubject(), user.getPreferredUsername(), user.getIssuer().toString());
    }

    record Profile(String subject, String username, String issuer) { }
}
