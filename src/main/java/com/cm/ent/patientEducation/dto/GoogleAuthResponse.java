package com.cm.ent.patientEducation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value
public class GoogleAuthResponse {
    String email;
    @JsonProperty("isPremium")
    boolean isPremium;
}