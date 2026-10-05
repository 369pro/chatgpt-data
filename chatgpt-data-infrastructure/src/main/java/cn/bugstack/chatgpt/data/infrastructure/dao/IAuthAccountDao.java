package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.AuthAccountPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAuthAccountDao {
    AuthAccountPO findByUsername(@Param("username") String username);
    int insert(AuthAccountPO account);
}
