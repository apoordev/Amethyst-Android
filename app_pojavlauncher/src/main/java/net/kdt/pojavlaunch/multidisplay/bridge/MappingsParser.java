package net.kdt.pojavlaunch.multidisplay.bridge;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for ProGuard-style obfuscation mappings.
 *
 * ProGuard format example:
 * net.minecraft.client.Minecraft -> abc:
 *     net.minecraft.client.player.LocalPlayer player -> a
 *     float getHealth() -> b
 *
 * This maps obfuscated names to their original Mojang names.
 */
public class MappingsParser {
    private static final String TAG = "MappingsParser";

    // Maps obfuscated class name -> original class name
    private final Map<String, String> classMap = new HashMap<>();

    // Maps obfuscated class name -> (obfuscated field/method -> original field/method)
    private final Map<String, Map<String, String>> fieldMap = new HashMap<>();
    private final Map<String, Map<String, String>> methodMap = new HashMap<>();

    // Reverse maps: original -> obfuscated (for lookup)
    private final Map<String, String> reverseClassMap = new HashMap<>();
    private final Map<String, Map<String, String>> reverseFieldMap = new HashMap<>();
    private final Map<String, Map<String, String>> reverseMethodMap = new HashMap<>();

    private boolean loaded = false;

    /**
     * Parse a ProGuard mappings file.
     * @param mappingsFile The .txt mappings file
     * @return true if parsing succeeded
     */
    public boolean parse(File mappingsFile) {
        if (!mappingsFile.exists()) {
            Log.e(TAG, "Mappings file not found: " + mappingsFile.getAbsolutePath());
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(mappingsFile))) {
            String line;
            String currentObfuscatedClass = null;
            String currentOriginalClass = null;

            while ((line = reader.readLine()) != null) {
                // Skip comments and empty lines
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }

                // Class mapping line: "original.ClassName -> obfuscated:"
                if (!line.startsWith(" ") && line.contains(" -> ") && line.endsWith(":")) {
                    String[] parts = line.substring(0, line.length() - 1).split(" -> ");
                    if (parts.length == 2) {
                        currentOriginalClass = parts[0].trim();
                        currentObfuscatedClass = parts[1].trim();

                        classMap.put(currentObfuscatedClass, currentOriginalClass);
                        reverseClassMap.put(currentOriginalClass, currentObfuscatedClass);

                        fieldMap.put(currentObfuscatedClass, new HashMap<>());
                        methodMap.put(currentObfuscatedClass, new HashMap<>());
                        reverseFieldMap.put(currentOriginalClass, new HashMap<>());
                        reverseMethodMap.put(currentOriginalClass, new HashMap<>());
                    }
                }
                // Field/method mapping line: "    type originalName -> obfuscatedName"
                else if (line.startsWith("    ") && currentObfuscatedClass != null) {
                    String trimmed = line.trim();
                    String[] parts = trimmed.split(" -> ");
                    if (parts.length == 2) {
                        String obfuscatedName = parts[1].trim();
                        String signature = parts[0].trim();

                        // Check if it's a method (contains parentheses)
                        if (signature.contains("(")) {
                            // Method: "returnType methodName(params) -> obfuscated"
                            // Extract just the method name
                            int parenIndex = signature.indexOf('(');
                            int spaceIndex = signature.lastIndexOf(' ', parenIndex);
                            if (spaceIndex > 0 && parenIndex > spaceIndex) {
                                String originalName = signature.substring(spaceIndex + 1, parenIndex);

                                Map<String, String> methods = methodMap.get(currentObfuscatedClass);
                                if (methods != null) {
                                    methods.put(obfuscatedName, originalName);
                                }

                                Map<String, String> reverseMethods = reverseMethodMap.get(currentOriginalClass);
                                if (reverseMethods != null) {
                                    reverseMethods.put(originalName, obfuscatedName);
                                }
                            }
                        } else {
                            // Field: "type fieldName -> obfuscated"
                            int spaceIndex = signature.lastIndexOf(' ');
                            if (spaceIndex > 0) {
                                String originalName = signature.substring(spaceIndex + 1);

                                Map<String, String> fields = fieldMap.get(currentObfuscatedClass);
                                if (fields != null) {
                                    fields.put(obfuscatedName, originalName);
                                }

                                Map<String, String> reverseFields = reverseFieldMap.get(currentOriginalClass);
                                if (reverseFields != null) {
                                    reverseFields.put(originalName, obfuscatedName);
                                }
                            }
                        }
                    }
                }
            }

            loaded = true;
            Log.i(TAG, "Loaded " + classMap.size() + " class mappings");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Error parsing mappings file", e);
            return false;
        }
    }

    /**
     * Get the obfuscated class name for an original class name.
     */
    public String getObfuscatedClassName(String originalName) {
        return reverseClassMap.getOrDefault(originalName, originalName);
    }

    /**
     * Get the original class name for an obfuscated class name.
     */
    public String getOriginalClassName(String obfuscatedName) {
        return classMap.getOrDefault(obfuscatedName, obfuscatedName);
    }

    /**
     * Get the obfuscated field name for an original field in a class.
     */
    public String getObfuscatedFieldName(String originalClassName, String originalFieldName) {
        Map<String, String> fields = reverseFieldMap.get(originalClassName);
        if (fields != null) {
            return fields.getOrDefault(originalFieldName, originalFieldName);
        }
        return originalFieldName;
    }

    /**
     * Get the obfuscated method name for an original method in a class.
     */
    public String getObfuscatedMethodName(String originalClassName, String originalMethodName) {
        Map<String, String> methods = reverseMethodMap.get(originalClassName);
        if (methods != null) {
            return methods.getOrDefault(originalMethodName, originalMethodName);
        }
        return originalMethodName;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Clear all mappings (for version changes).
     */
    public void clear() {
        classMap.clear();
        fieldMap.clear();
        methodMap.clear();
        reverseClassMap.clear();
        reverseFieldMap.clear();
        reverseMethodMap.clear();
        loaded = false;
    }
}
