package dev.queryguard.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** snake_case everywhere, enums case-insensitive, unknown keys are an error (typos in rule files are real). */
public final class ConfigMapper {

    private static final ObjectMapper YAML = YAMLMapper.builder(new YAMLFactory())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ObjectMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ConfigMapper() {
    }

    public static ObjectMapper yaml() {
        return YAML;
    }

    public static ObjectMapper json() {
        return JSON;
    }

    public static QueryGuardConfig load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return YAML.readValue(in, QueryGuardConfig.class);
        }
    }

    public static QueryGuardConfig load(InputStream in) throws IOException {
        return YAML.readValue(in, QueryGuardConfig.class);
    }
}
