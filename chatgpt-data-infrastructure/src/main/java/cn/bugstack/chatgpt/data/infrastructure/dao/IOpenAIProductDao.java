package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.po.OpenAIProductPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 商品Dao
 * @create 2023-10-05 13:27
 */
@Mapper
public interface IOpenAIProductDao {

    OpenAIProductPO queryProductByProductId(Integer productId);

}
