package com.storystylist.backend.photo.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PhotoUploadTomcatConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadContinueResponseTiming() {
        // Let multipart limits reject an oversized Content-Length before inviting the body.
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setProperty("continueResponseTiming", "onRead"));
    }
}
