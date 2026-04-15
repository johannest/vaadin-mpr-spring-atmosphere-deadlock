package org.vaadin.mprdemo;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;

/**
 * Login page for form-based authentication.
 * <p>
 * The {@link LoginForm} component POSTs credentials to Spring Security's
 * {@code /login} endpoint. In SAML2 mode, this page is not used — the
 * browser is redirected directly to the IdP login page.
 */
@Route("login")
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassName("login-view");
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        // POST to Spring Security's login processing URL
        loginForm.setAction("login");

        add(
                new H2("MPR Deadlock Demo"),
                new Paragraph("Login with user/user"),
                loginForm
        );
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Show error message if login failed
        if (event.getLocation().getQueryParameters()
                .getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }
}
