package com.example.enterpriseagent.intent;

import java.util.List;
import java.util.Objects;

import static com.example.enterpriseagent.intent.IntentModel.*;

/** 生成识别回复，并在读取回执时附加执行状态和可用的结果。 */
public final class ResponseComposer {
    public String reply(List<Outcome> outcomes) {
        return String.join(" ", outcomes.stream().map(Outcome::question).filter(Objects::nonNull).toList());
    }

    public Receipt withExecutions(Receipt receipt, List<Execution> executions) {
        return new Receipt(receipt.turnId(), receipt.assistantMessageId(), receipt.outcomes(), receipt.reply(), executions);
    }
}
