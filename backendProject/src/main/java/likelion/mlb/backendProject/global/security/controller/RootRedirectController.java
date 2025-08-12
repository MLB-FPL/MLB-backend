package likelion.mlb.backendProject.global.security.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootRedirectController {

  @GetMapping("/")
  public void redirectToLogin(HttpServletResponse response) throws IOException {
    response.sendRedirect("/login.html");
  }
}
