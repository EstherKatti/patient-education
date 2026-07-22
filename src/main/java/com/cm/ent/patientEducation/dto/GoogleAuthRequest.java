package com.cm.ent.patientEducation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Value;

@Value
public class GoogleAuthRequest {
    @NotBlank(message = "ID token is required")
    String idToken;
}