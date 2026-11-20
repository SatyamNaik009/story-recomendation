package com.storystylist.backend.story.service;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("story.render.text")
public record TextLayoutProperties(@DefaultValue("24") int minFontSize, @DefaultValue("72") int maxFontSize) {
    public TextLayoutProperties {
        if(minFontSize<8 || maxFontSize>144 || minFontSize>maxFontSize)
            throw new IllegalArgumentException("Text font limits must satisfy 8 <= min <= max <= 144");
    }
    public static TextLayoutProperties defaults() { return new TextLayoutProperties(24,72); }
}
