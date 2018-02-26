package org.heigit.bigspatialdata.ohsome.oshdbRestApi.config;

import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

@Configuration
public class JacksonConfig {

  @Autowired
  private ObjectMapper objectMapper;

  @PostConstruct
  public void setup() {
    objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

}