package cn.bugstack.chatgpt.data.domain.auth.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AccountCredentialEntity {
    private String username;
    private String openId;
    private String passwordHash;
}
