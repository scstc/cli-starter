package com.starter.auth.model;

import java.util.List;

public record UserInfo(String loginId, String username, List<String> roles, String device) {
}
