package service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class JsonService {

    private static final Gson GSON = new Gson();

    private JsonService() {
    }

    public static JsonObject parseObject(String json) {
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Request body must contain valid JSON.", e);
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("Expected a JSON object.");
        }
        return element.getAsJsonObject();
    }

    public static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string.");
        }

        String text = value.getAsString();
        if (text.trim().isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' must not be empty.");
        }
        return text;
    }

    public static String optionalString(JsonObject object, String field, String defaultValue) {
        JsonElement value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string.");
        }
        return value.getAsString();
    }

    public static int optionalInt(JsonObject object, String field, int defaultValue, int maximum) {
        JsonElement value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Field '" + field + "' must be an integer.");
        }
        double number = value.getAsDouble();
        int integer = value.getAsInt();
        if (!Double.isFinite(number) || number != integer || integer < 1 || integer > maximum) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' must be an integer between 1 and " + maximum + ".");
        }
        return integer;
    }

    public static String stringify(Object value) {
        return GSON.toJson(value);
    }

    public static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] response = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    public static Object error(String message) {
        return java.util.Map.of("error", message == null ? "Invalid request." : message);
    }
}
