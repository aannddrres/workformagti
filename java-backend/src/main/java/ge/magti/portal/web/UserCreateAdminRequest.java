package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mirrors schemas.py's UserCreateAdmin (schemas.py:525-536). */
public record UserCreateAdminRequest(
        // Column limits: past them Oracle refused the insert as a 500 (2026-10-02).
        @Email @NotBlank @Size(max = 255, message = "ელფოსტა 255 სიმბოლოზე გრძელი ვერ იქნება") String email,
        @NotBlank @Size(max = 200, message = "სახელი 200 სიმბოლოზე გრძელი ვერ იქნება") String name,
        @Size(max = 200, message = "დეპარტამენტი 200 სიმბოლოზე გრძელი ვერ იქნება") String department,
        @Size(max = 200, message = "პოზიცია 200 სიმბოლოზე გრძელი ვერ იქნება") String position,
        @Size(max = 30, message = "ტელეფონი 30 სიმბოლოზე გრძელი ვერ იქნება") String phone,
        String role,
        @NotBlank String password,
        @JsonProperty("team_id") Long teamId
) {
    public String roleOrDefault() {
        return (role == null || role.isBlank()) ? "operator" : role;
    }
}
