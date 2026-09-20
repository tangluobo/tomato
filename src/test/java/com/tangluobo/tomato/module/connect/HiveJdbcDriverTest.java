package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import java.sql.Driver;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HiveJdbcDriverTest {

    @Test
    void standaloneDriverCanBeLoadedAndInstantiated() throws Exception {
        Object driver = Class.forName("org.apache.hive.jdbc.HiveDriver")
                .getDeclaredConstructor()
                .newInstance();

        assertInstanceOf(Driver.class, driver);
        assertNotNull(XMLInputFactory.newFactory());
        assertNotNull(XMLOutputFactory.newFactory());
        assertNotNull(XMLEventFactory.newFactory());
    }
}
