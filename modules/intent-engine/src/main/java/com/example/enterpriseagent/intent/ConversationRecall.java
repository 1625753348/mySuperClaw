package com.example.enterpriseagent.intent;

import java.util.regex.Pattern;

/** 将回顾操作与轮次范围分别解析，避免为每种问法建立单独意图。 */
public record ConversationRecall(int offset, int limit, boolean document) {
    public static final String INTENT = "SYSTEM.RECALL_PREVIOUS_MESSAGE";
    public static final String DOCUMENT_INTENT = "SYSTEM.CONVERSATION_DOCUMENT";
    public static final int MAX_TURNS = 20;
    private static final String NUMBER = "[0-9零一二两三四五六七八九十百]+";
    private static final String UNIT = "(?:轮|条|次|个问题)";
    private static final String RANGE = "(?:上(?:一)?轮|上(?:一)?[条次]|刚刚|刚才|上一轮|上一个问题|近期|最近"
            + "|(?:上|前|近|最近|过去|这)几轮|(?:上|前|近|最近|过去)" + NUMBER + UNIT + "|倒数(?:第)?" + NUMBER + UNIT
            + "|" + NUMBER + UNIT + "(?:以前|之前|前))";
    private static final String QUESTION = "(?:(?:都|分别)?(?:问(?:了|过)?(?:你|过你|你过)?|说(?:了|过)?|提(?:了|过)?出?)"
            + "(?:的(?:是)?)?(?:些)?(?:什么|哪些)(?:问题|内容)?|问题是什么|(?<=问题)是什么)";
    private static final String REQUEST = "(?:(?:麻烦|请(?:问)?)?[，,]?(?:帮我)?)";
    private static final Pattern QUESTION_WITH_RANGE = Pattern.compile("^" + REQUEST + "(?:我(?:在)?)?"
            + "(?<range>" + RANGE + ")?[，,]?(?:我)?(?:的)?"
            + QUESTION + "(?:呢|啊|呀)?$");
    private static final String TRANSCRIPT = "(?:我(?:们)?(?:的)?)?(?<range>" + RANGE + ")?(?:的)?"
            + "(?:对话(?:记录)?|聊天(?:记录)?|会话记录|问答(?:记录)?)";
    private static final String ORGANIZE = "(?:整理|汇总|总结)";
    private static final String FILE = "(?:成|为|一下)?(?:一(?:份|篇))?"
            + "(?:markdown(?:格式)?(?:的)?(?:文档|文件)?|文档|文件)";
    private static final Pattern DOCUMENT = Pattern.compile("^" + REQUEST
            + "(?:把|将)?" + TRANSCRIPT + "(?:帮我)?" + ORGANIZE + FILE + "$");
    private static final Pattern ORGANIZE_TRANSCRIPT = Pattern.compile("^" + REQUEST + ORGANIZE
            + "(?:一下)?" + TRANSCRIPT + FILE + "$");
    private static final Pattern NUMBER_IN_RANGE = Pattern.compile(NUMBER);

    public String intent() {
        return document ? DOCUMENT_INTENT : INTENT;
    }

    public static ConversationRecall parse(String text) {
        String normalized = text.replaceAll("\\s+", "").replaceFirst("[。！!？?]+$", "");
        var document = DOCUMENT.matcher(normalized);
        if (document.matches()) return selection(document.group("range"), true);
        document = ORGANIZE_TRANSCRIPT.matcher(normalized);
        if (document.matches()) return selection(document.group("range"), true);
        var question = QUESTION_WITH_RANGE.matcher(normalized);
        return question.matches() ? selection(question.group("range"), false) : null;
    }

    private static ConversationRecall selection(String range, boolean document) {
        if (range == null) return new ConversationRecall(0, document ? 5 : 1, document);
        if ("上一个问题".equals(range)) return new ConversationRecall(0, 1, document);
        var count = NUMBER_IN_RANGE.matcher(range);
        if (!count.find()) {
            boolean recent = "近期".equals(range) || "最近".equals(range) || range.endsWith("几轮");
            return new ConversationRecall(0, recent ? 5 : 1, document);
        }
        int turns = number(count.group());
        if (turns < 1 || turns > MAX_TURNS) return new ConversationRecall(0, 0, document);
        boolean specific = range.startsWith("倒数") || range.endsWith("前");
        return new ConversationRecall(specific ? turns - 1 : 0, specific ? 1 : turns, document);
    }

    private static int number(String value) {
        if (value.matches("[0-9]{1,2}")) return Integer.parseInt(value);
        String[] parts = value.replace('两', '二').split("十", -1);
        if (parts.length == 1) return digit(parts[0]);
        if (parts.length != 2) return -1;
        int tens = parts[0].isEmpty() ? 1 : digit(parts[0]);
        int units = parts[1].isEmpty() ? 0 : digit(parts[1]);
        return tens > 0 && units >= 0 ? tens * 10 + units : -1;
    }

    private static int digit(String value) {
        return value.length() == 1 ? "零一二三四五六七八九".indexOf(value.charAt(0)) : -1;
    }
}
