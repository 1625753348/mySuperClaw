package com.example.enterpriseagent.intent;

import java.text.Normalizer;
import java.util.Locale;

import static com.example.enterpriseagent.intent.IntentArtifacts.*;
import static com.example.enterpriseagent.intent.IntentModel.*;

/** 归一化文本，保留由接入层装配的会话、回复对象和幂等信息。 */
public final class InputNormalizer {
    public NormalizedInput normalize(RawRequest raw) {
        Request request = raw.request();
        if (request == null || (request.text() == null || request.text().isBlank()) && request.skillKey() == null) {
            throw new Failure(400, "请输入文本或已登记的 Skill Key");
        }
        if (request.arguments() != null && request.arguments().size() > 24) {
            throw new Failure(400, "参数数量过多");
        }
        String text = request.text() == null ? "" : Normalizer.normalize(request.text(), Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        return new NormalizedInput(raw, text);
    }
}
