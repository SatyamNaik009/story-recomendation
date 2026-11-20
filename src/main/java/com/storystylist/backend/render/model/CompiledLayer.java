package com.storystylist.backend.render.model;
import com.fasterxml.jackson.annotation.*;
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="type")
@JsonSubTypes({@JsonSubTypes.Type(value=CompiledImageLayer.class,name="IMAGE"),@JsonSubTypes.Type(value=CompiledTextLayer.class,name="TEXT")})
public sealed interface CompiledLayer permits CompiledImageLayer,CompiledTextLayer { int order(); }
