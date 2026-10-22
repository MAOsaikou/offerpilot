package com.offerpilot.career.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.CareerProfileService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/career")
public class CareerProfileController {
    @ModelAttribute
    void preventCaching(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private final CareerProfileService profiles;
    private final CurrentUser users;

    public CareerProfileController(CareerProfileService profiles, CurrentUser users) {
        this.profiles = profiles;
        this.users = users;
    }

    // Regex lengths count Unicode code points, matching PostgreSQL char_length.
    public record ProfileRequest(
            @JsonDeserialize(using = StringOnlyDeserializer.class)
            @NotNull @Pattern(regexp = "(?s).{0,100}") String target,
            @JsonDeserialize(using = StringOnlyDeserializer.class)
            @NotNull @Pattern(regexp = "(?s).{0,100}") String city,
            @JsonDeserialize(using = StringOnlyDeserializer.class)
            @NotNull @Pattern(regexp = "(?s).{0,3000}") String skills,
            @JsonDeserialize(contentUsing = StringOnlyDeserializer.class)
            @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = "(?s).{0,50}") String> interests,
            @JsonDeserialize(using = StringOnlyDeserializer.class)
            @Pattern(regexp = "(?s).{0,100}") String major,
            @JsonDeserialize(using = StringOnlyDeserializer.class)
            @Pattern(regexp = "(?s).{0,3000}") String courses,
            @JsonDeserialize(using = StringOnlyDeserializer.class)
            @Pattern(regexp = "(?s).{0,1000}") String valuesText) {}

    public record TasksRequest(@JsonDeserialize(contentUsing = StringOnlyDeserializer.class)
                               @NotNull @Size(max = 6) List<@NotNull String> completed) {}

    /** Reject JSON numbers/booleans instead of Jackson's default coercion into strings. */
    public static class StringOnlyDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING))
                return (String) context.handleUnexpectedToken(String.class, parser);
            return parser.getText();
        }
    }

    @GetMapping("/profile")
    public ApiResponse<CareerProfileService.Profile> get(HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(profiles.load(owner), TraceIdFilter.current(request));
    }

    @PutMapping("/profile")
    public ApiResponse<CareerProfileService.Profile> save(@Valid @RequestBody ProfileRequest body,
                                                         HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(profiles.save(owner, body.target(), body.city(), body.skills(), body.interests(),
                        body.major(), body.courses(), body.valuesText()),
                TraceIdFilter.current(request));
    }

    @PutMapping("/tasks")
    public ApiResponse<CareerProfileService.Profile> replaceTasks(@Valid @RequestBody TasksRequest body,
                                                                 HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(profiles.replaceTasks(owner, body.completed()), TraceIdFilter.current(request));
    }
}
