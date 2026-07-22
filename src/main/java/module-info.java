module tomato {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires com.google.gson;
    requires java.desktop;

    opens com.tangluobo.tomato to javafx.fxml;
    exports com.tangluobo.tomato;
    exports com.tangluobo.tomato.module;
    opens com.tangluobo.tomato.module to javafx.fxml;
    exports com.tangluobo.tomato.module.connect;
    opens com.tangluobo.tomato.module.connect to javafx.fxml, com.google.gson;
    exports com.tangluobo.tomato.module.settings;
    opens com.tangluobo.tomato.module.settings to javafx.fxml;
    exports com.tangluobo.tomato.module.tools;
    opens com.tangluobo.tomato.module.tools to javafx.fxml;
    exports com.tangluobo.tomato.utils;
    opens com.tangluobo.tomato.utils to com.google.gson, javafx.fxml;
}