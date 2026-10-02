package cn.bugstack.chatgpt.data.domain.openai.model.entity;

import lombok.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A tariff snapshot; estimates are only used for a refundable authorization. */
@Value
public class ChatBillingQuote {
    String model;
    BigDecimal inputPrice;
    BigDecimal cachedInputPrice;
    BigDecimal outputPrice;
    BigDecimal reservedAmount;

    public static ChatBillingQuote from(ChatModel model, List<MessageEntity> messages) {
        validateRate(model.getInputPrice());
        validateRate(model.getCachedInputPrice());
        validateRate(model.getOutputPrice());
        if (model.getCachedInputPrice().compareTo(model.getInputPrice()) > 0
                || model.getMaxOutputTokens() < 1 || model.getMaxOutputTokens() > 32768) {
            throw new IllegalArgumentException("Invalid model billing configuration");
        }
        long inputBudget = 1024;
        if (messages != null) for (MessageEntity message : messages) {
            if (message == null) continue;
            inputBudget += 256;
            for (String text : new String[]{message.getContent(), message.getRole(), message.getName()}) {
                if (text != null) inputBudget = Math.addExact(inputBudget,
                        Math.multiplyExact(2L, text.getBytes(StandardCharsets.UTF_8).length));
            }
        }
        BigDecimal reserve = cost(inputBudget, model.getMaxOutputTokens(), 0,
                model.getInputPrice(), model.getCachedInputPrice(), model.getOutputPrice());
        return new ChatBillingQuote(model.getId(), model.getInputPrice(), model.getCachedInputPrice(),
                model.getOutputPrice(), reserve);
    }

    public static BigDecimal cost(long input, long output, long cached, BigDecimal inputPrice,
                                  BigDecimal cachedPrice, BigDecimal outputPrice) {
        if (input < 0 || output < 0 || cached < 0 || cached > input)
            throw new IllegalArgumentException("Invalid token usage");
        return BigDecimal.valueOf(input - cached).multiply(inputPrice)
                .add(BigDecimal.valueOf(cached).multiply(cachedPrice))
                .add(BigDecimal.valueOf(output).multiply(outputPrice))
                .divide(new BigDecimal("1000000"), 8, RoundingMode.CEILING);
    }

    private static void validateRate(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 8
                || value.compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalArgumentException("Model token tariff is missing or invalid");
    }
}
