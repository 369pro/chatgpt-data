package cn.bugstack.chatgpt.data.domain.order.repository;

import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.PayOrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ProductEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.UnpaidOrderEntity;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 订单仓储接口
 * @create 2023-10-05 13:11
 */
public interface IOrderRepository {


    UnpaidOrderEntity queryUnpaidOrder(ShopCartEntity shopCartEntity);

    ProductEntity queryProduct(Integer productId);

    void saveOrder(CreateOrderAggregate aggregate);

    void updateOrderPayInfo(PayOrderEntity payOrderEntity);

}
