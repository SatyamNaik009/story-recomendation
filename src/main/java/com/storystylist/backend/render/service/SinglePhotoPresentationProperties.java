package com.storystylist.backend.render.service;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("story.render.single")
public record SinglePhotoPresentationProperties(@DefaultValue("true") boolean refinementEnabled) {}
