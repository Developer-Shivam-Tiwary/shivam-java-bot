package com.shivam.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatbotController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final Pattern EMAIL_RX = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern NAME_RX  = Pattern.compile("^[A-Za-z][A-Za-z\\s.'-]{1,60}$");
    private static final Pattern PHONE_RX = Pattern.compile("^\\+?[0-9]{10,15}$");

    @Value("${NVIDIA_API_KEY:}")
    private String nvidiaKey;

    // Render Environment Variable se model select hoga!
    @Value("${NVIDIA_MODEL:nvidia/llama-3.3-nemotron-super-49b-v1.5}")
    private String nvidiaModel;

    @Value("${RESEND_API_KEY:}")
    private String resendKey;

    @Value("${NOTIFY_EMAIL:shivamtiwaryniet@gmail.com}")
    private String notifyEmail;

    public ChatbotController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "model", nvidiaModel);
    }

    // 1. Verify Input (Regex + TiDB Duplicate Check + AI Model Verification)
    @PostMapping("/verify-input")
    public Map<String, Object> verifyInput(@RequestBody Map<String, String> body) {
        String field = clean(body.get("field")).toLowerCase();
        String value = clean(body.get("value"));

        switch (field) {
            case "email": {
                String email = value.toLowerCase();
                if (!EMAIL_RX.matcher(email).matches()) {
                    return Map.of("valid", false, "message",
                        "That does not look like a valid email address. Please enter a real email (e.g., john@example.com).");
                }
                // Check TiDB duplicate first
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT full_name FROM visitors WHERE email = ? LIMIT 1", email
                );
                if (!rows.isEmpty()) {
                    String oldName = String.valueOf(rows.get(0).get("full_name"));
                    return Map.of(
                        "valid", true,
                        "returning", true,
                        "name", oldName,
                        "message", "Welcome back, " + oldName + "! Great to see you again. How can I assist you today?"
                    );
                }
                // AI model check for fake/gibberish emails
                String aiCheck = verifyWithAI("email", email);
                if (aiCheck != null) {
                    return Map.of("valid", false, "message", aiCheck);
                }
                return Map.of("valid", true, "returning", false);
            }

            case "name": {
                if (!NAME_RX.matcher(value).matches() || value.trim().length() < 2) {
                    return Map.of("valid", false, "message",
                        "Please enter a valid full name using letters only (e.g., Rahul Sharma).");
                }
                String aiCheck = verifyWithAI("name", value);
                if (aiCheck != null) {
                    return Map.of("valid", false, "message", aiCheck);
                }
                return Map.of("valid", true);
            }

            case "phone": {
                if (value.equalsIgnoreCase("skip")) {
                    return Map.of("valid", true, "skipped", true, "returning", false);
                }
                String phone = value.replaceAll("[\\s()-]", "");
                if (!PHONE_RX.matcher(phone).matches()) {
                    return Map.of("valid", false, "message",
                        "Please enter a valid 10 to 15 digit phone number, or type 'skip' to continue.");
                }
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT full_name FROM visitors WHERE phone = ? LIMIT 1", phone
                );
                if (!rows.isEmpty()) {
                    String oldName = String.valueOf(rows.get(0).get("full_name"));
                    return Map.of(
                        "valid", true,
                        "returning", true,
                        "name", oldName,
                        "message", "Welcome back, " + oldName + "! Your phone number is already registered. How can I help you today?"
                    );
                }
                return Map.of("valid", true, "returning", false, "cleaned", phone);
            }

            case "work": {
                if (value.length() < 4) {
                    return Map.of("valid", false, "message",
                        "Please share a few more details about your project, job inquiry, or purpose.");
                }
                String aiCheck = verifyWithAI("work", value);
                if (aiCheck != null) {
                    return Map.of("valid", false, "message", aiCheck);
                }
                return Map.of("valid", true);
            }

            default:
                return Map.of("valid", true);
        }
    }

    // 2. Save Verified Visitor in TiDB + Send Email Alert
    @PostMapping("/save-visitor")
    public Map<String, Object> saveVisitor(@RequestBody Map<String, String> body) {
        String name = clean(body.get("name"));
        String email = clean(body.get("email")).toLowerCase();
        String phone = clean(body.get("phone")).replaceAll("[\\s()-]", "");
        String work = clean(body.get("work"));

        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id, full_name FROM visitors " +
            "WHERE (? <> '' AND email = ?) OR (? <> '' AND phone = ?) LIMIT 1",
            email, email, phone, phone
        );

        if (!existing.isEmpty()) {
            long id = ((Number) existing.get(0).get("id")).longValue();
            String oldName = String.valueOf(existing.get(0).get("full_name"));
            jdbc.update(
                "UPDATE visitors SET work_details = ?, visit_count = visit_count + 1 WHERE id = ?",
                work, id
            );
            sendEmailAlert(oldName + " (Returning)", email, phone, work);
            return Map.of("returning", true, "name", oldName, "saved", true);
        }

        jdbc.update(
            "INSERT INTO visitors (full_name, email, phone, work_details) VALUES (?, ?, ?, ?)",
            name,
            email.isEmpty() ? null : email,
            phone.isEmpty() ? null : phone,
            work
        );

        sendEmailAlert(name, email, phone, work);
        return Map.of("returning", false, "name", name, "saved", true);
    }

    // 3. Full English AI Chat (After Verification)
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String msg = clean(body.get("message"));
        String visitorName = clean(body.get("visitorName"));
        String work = clean(body.get("work"));

        String systemPrompt =
            "You are Shivam Tiwary's official portfolio AI assistant. Always reply in clear, polite, professional English. " +
            "Shivam Tiwary is an MCA student (NIET, Greater Noida, 8.24 CGPA) and Full Stack Developer skilled in Core Java, JDBC, Spring Boot, Python, C, HTML/CSS, JavaScript, and MySQL. " +
            "The verified visitor's name is " + (visitorName.isEmpty() ? "Guest" : visitorName) +
            (work.isEmpty() ? "" : " and their stated purpose is: " + work) + ". " +
            "Answer concisely in 2-4 sentences.";

        String reply = callNemotron(systemPrompt, msg, 240, 0.5);
        if (reply == null || reply.isEmpty()) {
            reply = "Thank you for your message, " + (visitorName.isEmpty() ? "friend" : visitorName) +
                    "! Shivam will review your request and follow up via email shortly.";
        }
        return Map.of("reply", reply);
    }

    // Helper: Uses NVIDIA Nemotron to verify genuine Name / Email / Work
    private String verifyWithAI(String field, String value) {
        if (nvidiaKey == null || nvidiaKey.isEmpty()) return null;

        String rule;
        if ("name".equals(field)) {
            rule = "The user was asked for their Full Name. Decide if \"" + value + "\" is a plausible real person's name. " +
                   "Reject greetings (hi, hello, hey), questions, random gibberish (asdf, xyz), or non-names (test, admin, nobody, ok).";
        } else if ("email".equals(field)) {
            rule = "The user was asked for their Email Address. Decide if \"" + value + "\" looks like a genuine personal or work email (not obvious fake gibberish like asdf@asdf.com or fake@fake.com).";
        } else {
            rule = "The user was asked for the Work / Project / Reason they are contacting Shivam Tiwary (a software developer). " +
                   "Decide if \"" + value + "\" is a meaningful purpose or inquiry (reject random gibberish like 'asdfgh', 'hi', 'nothing', 'idk', '1234').";
        }

        String sys = "You are a strict form input validator. " +
            "Reply ONLY with the exact word VALID if the input passes, OR reply starting with INVALID: followed by a polite 1-sentence English message asking the user to enter a genuine " + field + ".";

        String aiRes = callNemotron(sys, rule, 80, 0.1);
        if (aiRes != null && aiRes.toUpperCase().startsWith("INVALID:")) {
            return aiRes.substring(8).trim();
        }
        return null;
    }

    private String callNemotron(String systemPrompt, String userPrompt, int maxTokens, double temp) {
        if (nvidiaKey == null || nvidiaKey.isEmpty()) return null;
        try {
            Map<String, Object> payload = Map.of(
                "model", nvidiaModel,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", maxTokens,
                "temperature", temp
            );

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://integrate.api.nvidia.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + nvidiaKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) return null;

            JsonNode root = mapper.readTree(res.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            return content == null ? null : content.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void sendEmailAlert(String name, String email, String phone, String work) {
        if (resendKey == null || resendKey.isEmpty()) return;
        try {
            String html = "<h3>New Verified Portfolio Inquiry</h3>" +
                "<p><b>Name:</b> " + esc(name) + "</p>" +
                "<p><b>Email:</b> " + esc(email) + "</p>" +
                "<p><b>Phone:</b> " + esc(phone.isEmpty() ? "N/A" : phone) + "</p>" +
                "<p><b>Work / Purpose:</b> " + esc(work) + "</p>";

            Map<String, Object> mail = Map.of(
                "from", "Portfolio Bot <onboarding@resend.dev>",
                "to", List.of(notifyEmail),
                "subject", "Verified Portfolio Lead: " + name,
                "html", html
            );

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .header("Authorization", "Bearer " + resendKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mail)))
                .build();

            http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    private String clean(String s) { return s == null ? "" : s.trim(); }
    private String esc(String s) {
        return clean(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
