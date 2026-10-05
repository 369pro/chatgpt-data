package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.Data;

@Data
public class AuthAccountPO {
    private String username;
    private String openId;
    private String passwordHash;
}
