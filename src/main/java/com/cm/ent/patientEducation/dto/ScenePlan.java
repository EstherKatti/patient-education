package com.cm.ent.patientEducation.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonClassDescription("An ordered set of scenes that together teach one patient-education topic.")
public class ScenePlan {
    @JsonPropertyDescription("The scenes in the order they should play, first to last.")
    List<Scene> scenes;
}