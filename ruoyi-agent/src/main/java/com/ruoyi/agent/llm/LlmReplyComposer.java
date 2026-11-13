package com.ruoyi.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ruoyi.agent.core.ReplyComposer;

/**
 * 用大模型把工具事实写成一段话。
 * <p>
 * 它只做「改写」：输入是已经算好的事实清单，输出是给人读的句子。
 * 数字由 {@link com.ruoyi.agent.core.FactGuard} 事后核对，一个都对不上就整段弃用，
 * 回到工具提供的模板正文——所以这里可以放心让它自由措辞，因为它没有编数字的余地。
 *
 * @author ruoyi
 */
public class LlmReplyComposer implements ReplyComposer
{
    private static final Logger log = LoggerFactory.getLogger(LlmReplyComposer.class);

    /** 超过这个长度说明模型开始写小作文了，直接弃用 */
    private static final int MAX_CHARS = 600;

    private static final String SYSTEM = """
            你是数据中心微网调度系统的「回话」环节。上一步已经查完数据，你只负责把给到的事实讲成调度员会说的话。

            硬规则：
            1. 只能使用事实里出现的数字、日期、站点名。不许新造数字，不许四舍五入、换算、估算或加总。
            2. 事实里没有的字段不要提（没给储能 SOC 就别聊储能，没给碳排就别聊碳排）。
            3. 2 到 4 句中文，先说结论再说依据；可以点出值得注意的地方，比如峰值出现在几点、光伏此时为什么是 0、电价最高时段。
               不要写「值得留意」「需要注意」这种没有下文的评价——要么把话说清（哪一时段、高多少），要么不说。
            4. 消息里会给出今天的日期。数据日期就是今天，正常说即可；不是今天，第一句就要说清是哪天的。
            5. 不要「查询成功」「根据提供的信息」「以下是」这类废话；不要 markdown、不要分点、不要引号。
            6. 不用复述每一个字段，挑用户问的那件事说透。
               不要写「事实里没有给出」「无法判断」这类元信息——缺的字段直接不提。
               也不要汇报「采样点已收齐」这类送达语，直接说数据和判断。
            7. 不要下「运行平稳」「一切正常」「无异常」这类结论：没查故障和告警，就只描述数据本身。
            """;

    private final DeepSeekClient client;

    public LlmReplyComposer(DeepSeekClient client)
    {
        this.client = client;
    }

    @Override
    public boolean available()
    {
        return client != null && client.configured();
    }

    @Override
    public Composed compose(Request request)
    {
        if (!available() || request == null)
        {
            return null;
        }
        try
        {
            DeepSeekClient.Completion c = client.complete(SYSTEM, buildUser(request), 0.3, 260);
            String text = c.content() == null ? "" : c.content().trim();
            if (text.isEmpty())
            {
                return null;
            }
            if (text.length() > MAX_CHARS)
            {
                log.info("[agent] 撰写结果 {} 字，超过上限 {}，改用模板正文", text.length(), MAX_CHARS);
                return null;
            }
            return new Composed(text, c.totalTokens());
        }
        catch (Exception e)
        {
            log.warn("[agent] 正文撰写失败，回落模板正文：{}", e.getMessage());
            return null;
        }
    }

    private String buildUser(Request req)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("今天是 ").append(java.time.LocalDate.now()).append("。\n");
        sb.append("用户问：").append(req.userText() == null ? "（追问）" : req.userText()).append('\n');
        sb.append("工具：").append(req.toolTitle() == null ? "" : req.toolTitle()).append('\n');
        if (req.headline() != null && !req.headline().isBlank())
        {
            sb.append("结论：").append(req.headline()).append('\n');
        }
        sb.append("事实：\n");
        if (req.facts() != null)
        {
            for (String f : req.facts())
            {
                sb.append("- ").append(f).append('\n');
            }
        }
        return sb.toString();
    }
}
