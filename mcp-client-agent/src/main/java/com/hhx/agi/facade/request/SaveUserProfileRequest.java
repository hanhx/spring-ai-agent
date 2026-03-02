package com.hhx.agi.facade.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SaveUserProfileRequest {

    @NotBlank(message = "userId cannot be empty")
    private String userId;

    @NotBlank(message = "profileKey cannot be empty")
    private String profileKey;

    @NotBlank(message = "profileValue cannot be empty")
    private String profileValue;

    private String sourceMessage;
}