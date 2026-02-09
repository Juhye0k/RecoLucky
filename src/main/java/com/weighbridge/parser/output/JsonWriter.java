package com.weighbridge.parser.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.weighbridge.parser.model.ParsedDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ParsedDocument → JSON 출력.
 */
public class JsonWriter {

    private final ObjectMapper mapper;

    public JsonWriter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * ParsedDocument를 JSON 문자열로 변환.
     */
    public String toJson(ParsedDocument doc) throws IOException {
        return mapper.writeValueAsString(doc);
    }

    /**
     * ParsedDocument를 파일에 JSON으로 출력.
     */
    public void write(ParsedDocument doc, Path outputPath) throws IOException {
        Files.writeString(outputPath, toJson(doc));
    }

    /**
     * ParsedDocument를 Writer에 JSON으로 출력.
     */
    public void write(ParsedDocument doc, Writer writer) throws IOException {
        writer.write(toJson(doc));
    }

    /**
     * ParsedDocument를 OutputStream에 JSON으로 출력.
     */
    public void write(ParsedDocument doc, OutputStream out) throws IOException {
        mapper.writeValue(out, doc);
    }
}
