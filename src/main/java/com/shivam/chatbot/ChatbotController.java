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

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Live hone ke baad "*" ki jagah apna blogspot URL daal sakte hain
public class ChatbotController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${NVIDIA_API_KEY:}")
    private String nvidiaKey;

    @Value("${RESEND_API_KEY:}")
    private String resendKey;

    @Value("${NOTIFY_EMAIL:shivamtiwaryniet@gmail.com}")
    private String notifyEmail;

    public ChatbotController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // 1. Duplicate Email / Phone Check
    @PostMapping("/check-visitor")
    public Map<String, Object> checkVisitor(@RequestBody Map<String, String> body) {
        String email = clean(body.get("email")).toLowerCase();
        String phone = clean(body.get("phone")).replaceAll("\\s+", "");

        if (email.isEmpty() && phone.isEmpty()) {
            return Map.of("returning", false);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT full_name, work_details FROM visitors " +
            "WHERE (? <> '' AND email = ?) OR (? <> '' AND phone = ?) LIMIT 1",
            email, email, phone, phone
        );

        if (!rows.isEmpty()) {
            return Map.of(
                "returning", true,
                "name", String.valueOf(rows.get(0).get("full_name")),
                "lastWork", String.valueOf(rows.get(0).get("work_details"))
            );
        }
        return Map.of("returning", false);
    }

    // 2. TiDB MySQL me Save + Email Notification
    @PostMapping("/save-visitor")
    public Map<String, Object> saveVisitor(@RequestBody Map<String, String> body) {
        String name = clean(body.get("name"));
        String email = clean(body.get("email")).toLowerCase();
        String phone = clean(body.get("phone")).replaceAll("\\s+", "");
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

    // 3. NVIDIA Nemotron AI Chat
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        try {
            String msg = clean(body.get("message"));
            String visitorName = clean(body.get("visitorName"));

            if (nvidiaKey.isEmpty()) {
                return Map.of("reply", "Thanks " + visitorName + "! Shivam will review your message shortly.");
            }

            Map<String, Object> payload = Map.of(
                "model", "nvidia/llama-3.1-nemotron-70b-instruct",
                "messages", List.of(
                    Map.of("role", "system", "content",
                        "You are Shivam Tiwary's friendly AI portfolio assistant. Shivam is an MCA student (NIET, 8.24 CGPA) and Full Stack Developer skilled in Core Java, JDBC, Python, C, HTML/CSS, JavaScript, and MySQL. Visitor's name is " +
                        (visitorName.isEmpty() ? "Guest" : visitorName) +
                        ". Answer in 2-4 crisp, helpful sentences."),
                    Map.of("role", "user", "content", msg)
                ),
                "max_tokens", 220,
                "temperature", 0.5
            );

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://integrate.api.nvidia.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + nvidiaKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(res.body());
            String reply = root.path("choices").path(0).path("message").path("content").asText();

            if (reply == null || reply.isEmpty()) {
                reply = "Thanks for reaching out! Shivam will connect with you soon.";
            }
            return Map.of("reply", reply.trim());
        } catch (Exception e) {
            return Map.of("reply", "Got it! Shivam will get back to you soon via email.");
        }
    }

    private void sendEmailAlert(String name, String email, String phone, String work) {
        if (resendKey == null || resendKey.isEmpty()) return;
        try {
            String html = "<h3>New Portfolio Chatbot Inquiry</h3>" +
                "<p><b>Name:</b> " + esc(name) + "</p>" +
                "<p><b>Email:</b> " + esc(email) + "</p>" +
                "<p><b>Phone:</b> " + esc(phone.isEmpty() ? "N/A" : phone) + "</p>" +
                "<p><b>Work / Requirement:</b> " + esc(work) + "</p>";

            Map<String, Object> mail = Map.of(
                "from", "Portfolio Bot <onboarding@resend.dev>",
                "to", List.of(notifyEmail),
                "subject", "Portfolio Bot Lead: " + name,
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
