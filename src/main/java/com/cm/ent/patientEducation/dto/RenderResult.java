package com.cm.ent.patientEducation.dto;

import com.cm.ent.patientEducation.constants.Mode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Uniform result of a render, whatever backend produced it.
 */
@Getter
@AllArgsConstructor
public class RenderResult {

    public enum Status {COMPLETED, FAILED, NOT_IMPLEMENTED}

    private final Mode mode;
    private final Status status;
    private final String outputUri;
    private final String message;

    public static RenderResult completed(Mode mode, String outputUri) {
        return new RenderResult(mode, Status.COMPLETED, outputUri, null);
    }

    public static RenderResult failed(Mode mode, String message) {
        return new RenderResult(mode, Status.FAILED, null, message);
    }

    public static RenderResult notImplemented(Mode mode, String message) {
        return new RenderResult(mode, Status.NOT_IMPLEMENTED, null, message);
    }
}