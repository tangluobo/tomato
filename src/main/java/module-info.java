module demo {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.tangluobo.tomato to javafx.fxml;
    exports com.tangluobo.tomato;
}