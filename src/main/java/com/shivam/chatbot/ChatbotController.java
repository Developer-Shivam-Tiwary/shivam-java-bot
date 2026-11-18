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

    private static final Pattern EMAIL_RX     = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern NAME_RX      = Pattern.compile("^[A-Za-z][A-Za-z\\s.'-]{1,60}$");
    private static final Pattern GIBBERISH_RX = Pattern.compile("(?i).*[bcdfghjklmnpqrstvwxyz]{6,}.*");
    private static final Pattern PHONE_RX     = Pattern.compile("^\\+?[0-9]{10,15}$");
	
  	@Value("${NVIDIA_MAX_TOKENS:}")
        private int maxChatTokens;	

    @Value("${NVIDIA_API_KEY:}")
    private String nvidiaKey;

    @Value("${NVIDIA_MODEL:}")
    private String nvidiaModel;

    @Value("${RESEND_API_KEY:}")
    private String resendKey;

    @Value("${NOTIFY_EMAIL:}")
    private String notifyEmail;

    public ChatbotController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "model", nvidiaModel);
    }

    // Browser me kholkar AI test karne ke liye: /api/test-ai
    @GetMapping("/test-ai")
    public Map<String, Object> testAi() {
        if (nvidiaKey == null || nvidiaKey.isEmpty()) {
            return Map.of("ok", false, "error", "NVIDIA_API_KEY is missing in Render Environment!");
        }
        String reply = callNemotron("/no_think\nYou are a helpful assistant.", "Say 'AI is working!' in 4 words.", 100, 0.2);
        return Map.of(
            "ok", reply != null && !reply.isEmpty(),
            "model", nvidiaModel,
            "reply", reply == null ? "FAILED - Check Render Logs for HTTP status" : reply
        );
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
                String aiCheck = verifyWithAI("email", email);
                if (aiCheck != null) {
                    return Map.of("valid", false, "message", aiCheck);
                }
                return Map.of("valid", true, "returning", false);
            }

            case "name": {
                if (!NAME_RX.matcher(value).matches() || value.trim().length() < 2 || GIBBERISH_RX.matcher(value).matches()) {
                    return Map.of("valid", false, "message",
                        "That doesn't look like a real name. Please enter your actual full name (e.g., Rahul Sharma).");
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
                if (value.length() < 4 || GIBBERISH_RX.matcher(value).matches()) {
                    return Map.of("valid", false, "message",
                        "Please briefly describe your project, job inquiry, or why you are contacting Shivam.");
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

   // 3. Full English AI Chat (With Detailed Context + History + Increased Tokens)
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, Object> body) {
        String msg = str(body.get("message"));
        String visitorName = str(body.get("visitorName"));
        String work = str(body.get("work"));

        String systemPrompt =
            "/no_think\n" +
            "You are Shivam Tiwary's official portfolio AI assistant. Always reply in clear, polite, professional English.\n" +
            "--- ABOUT SHIVAM TIWARY ---\n" +
            "Role: Full Stack Developer & MCA Student.\n" +
            "Education: Pursuing Master of Computer Applications (MCA, 2025-2027) at NIET, Greater Noida (AKTU) with 8.24/10 CGPA in Semester I. Completed B.Sc. Mathematics (2022-2025) from Shershah College, Sasaram (VKSU) with 67.6%.\n" +
            "Technical Skills: Core Java, JDBC, Spring Boot, Python, C Programming, HTML5, CSS3, JavaScript, MySQL, TiDB Cloud, Git, GitHub, Linux/Bash.\n" +
            "CS Fundamentals & Tools: OOP, Data Structures & Algorithms (DSA), DBMS, Operating Systems, VS Code, IntelliJ IDEA.\n" +
            "Contact Email: shivamtiwaryniet@gmail.com.\n" +
            "--- CURRENT VISITOR ---\n" +
            "Name: " + (visitorName.isEmpty() ? "Guest" : visitorName) + "\n" +
            (work.isEmpty() ? "" : "Stated Purpose: " + work + "\n") +
            "Answer helpfully, accurately, and concisely (2-5 sentences unless asked for more details).";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        Object rawHistory = body.get("history");
        if (rawHistory instanceof List<?> list) {
            int start = Math.max(0, list.size() - 10);
            for (int i = start; i < list.size(); i++) {
                if (list.get(i) instanceof Map<?, ?> item) {
                    String role = str(item.get("role"));
                    String content = str(item.get("content"));
                    if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                        messages.add(Map.of("role", role, "content", content));
                    }
                }
            }
        }
        messages.add(Map.of("role", "user", "content", msg));

        String reply = callNemotronMessages(messages, maxChatTokens, 0.5);
        if (reply == null || reply.isEmpty() || "null".equalsIgnoreCase(reply)) {
            reply = "Thank you for your message, " + (visitorName.isEmpty() ? "friend" : visitorName) +
                    "! Shivam will review your request and follow up via email shortly.";
        }
        return Map.of("reply", reply);
    }

    private String verifyWithAI(String field, String value) {
        if (nvidiaKey == null || nvidiaKey.isEmpty()) return null;

        String rule;
        if ("name".equals(field)) {
            rule = "The user was asked for their Full Name. Decide if \"" + value + "\" is a real human name. " +
                   "Reject greetings (hi, hello, hey), keyboard smash/gibberish (asdf, Johkjskjdkjf, qwert), test words (test, admin, nobody), or non-names.";
        } else if ("email".equals(field)) {
            rule = "The user was asked for their Email Address. Decide if \"" + value + "\" looks like a genuine personal or work email (reject obvious fake gibberish like asdf@asdf.com or fake@fake.com).";
        } else {
            rule = "The user was asked why they are contacting Shivam Tiwary (a software developer). " +
                   "Decide if \"" + value + "\" is a meaningful project/job/contact reason (reject gibberish like 'asdfgh', 'hi', 'nothing', 'idk', '1234').";
        }

        String sys = "/no_think\nYou are a strict form validator. " +
            "Reply ONLY with the single word VALID if it passes, OR reply starting with INVALID: followed by a polite 1-sentence English message asking for a genuine " + field + ".";

        String aiRes = callNemotron(sys, rule, 300, 0.1);
        if (aiRes != null && aiRes.toUpperCase().startsWith("INVALID:")) {
            return aiRes.substring(8).trim();
        }
        return null;
    }

    private String callNemotron(String systemPrompt, String userPrompt, int maxTokens, double temp) {
        return callNemotronMessages(
            List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            maxTokens,
            temp
        );
    }

    private String callNemotronMessages(List<Map<String, String>> messages, int maxTokens, double temp) {
        if (nvidiaKey == null || nvidiaKey.isEmpty()) return null;
        try {
            Map<String, Object> payload = Map.of(
                "model", nvidiaModel,
                "messages", messages,
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
            if (res.statusCode() >= 400) {
                System.err.println("NVIDIA API Error " + res.statusCode() + ": " + res.body());
                return null;
            }

            JsonNode msgNode = mapper.readTree(res.body()).path("choices").path(0).path("message");
            JsonNode contentNode = msgNode.path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return null;
            }
            String content = contentNode.asText("").replaceAll("(?s)<think>.*?</think>", "").trim();
            return (content.isEmpty() || "null".equalsIgnoreCase(content)) ? null : content;
        } catch (Exception e) {
            System.err.println("NVIDIA Call Exception: " + e.getMessage());
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
    private String str(Object o) { return o == null ? "" : clean(String.valueOf(o)); }
    private String esc(String s) {
        return clean(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
