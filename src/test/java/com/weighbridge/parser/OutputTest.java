package com.weighbridge.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weighbridge.parser.model.*;
import com.weighbridge.parser.output.JsonWriter;
import com.weighbridge.parser.pipeline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private ParsedDocument fullPipeline(String filename) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/" + filename);
        assertThat(is).as("Fixture %s", filename).isNotNull();
        OcrInput input = objectMapper.readValue(is, OcrInput.class);

        Preprocessor preprocessor = new Preprocessor();
        Extractor extractor = new Extractor();
        FieldAssigner fieldAssigner = new FieldAssigner();
        Normalizer normalizer = new Normalizer();
        Validator validator = new Validator();

        List<ProcessedLine> processed = preprocessor.process(input);
        List<ClassifiedLine> classified = extractor.classifyLines(processed);
        ParsedDocument doc = fieldAssigner.assign(classified, filename);
        doc = normalizer.normalize(doc);
        doc = validator.validate(doc);
        return doc;
    }

    @Test
    @DisplayName("sample_01 JSON 출력 → 파싱 가능")
    void jsonParseable() throws Exception {
        ParsedDocument doc = fullPipeline("sample_01.json");
        JsonWriter writer = new JsonWriter();
        String json = writer.toJson(doc);

        assertThat(json).isNotEmpty();
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.has("fields")).isTrue();
    }

    @Test
    @DisplayName("sample_01 JSON → document_type 존재")
    void jsonDocumentType() throws Exception {
        ParsedDocument doc = fullPipeline("sample_01.json");
        JsonWriter writer = new JsonWriter();
        String json = writer.toJson(doc);

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("document_type").asText()).isEqualTo("계량증명서");
    }

    @Test
    @DisplayName("sample_01 JSON → gross_weight 필드 존재")
    void jsonGrossWeight() throws Exception {
        ParsedDocument doc = fullPipeline("sample_01.json");
        JsonWriter writer = new JsonWriter();
        String json = writer.toJson(doc);

        JsonNode node = objectMapper.readTree(json);
        JsonNode grossNode = node.get("fields").get("gross_weight");
        assertThat(grossNode).isNotNull();
        assertThat(grossNode.get("value").asInt()).isEqualTo(12480);
    }

    @Test
    @DisplayName("sample_01 JSON → validation 존재")
    void jsonValidation() throws Exception {
        ParsedDocument doc = fullPipeline("sample_01.json");
        JsonWriter writer = new JsonWriter();
        String json = writer.toJson(doc);

        JsonNode node = objectMapper.readTree(json);
        JsonNode validation = node.get("validation");
        assertThat(validation).isNotNull();
        assertThat(validation.get("is_consistent").asBoolean()).isTrue();
        assertThat(validation.get("is_actionable").asBoolean()).isTrue();
    }
}
