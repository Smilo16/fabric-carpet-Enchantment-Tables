package carpet.script.exception;

import carpet.script.Context;
import carpet.script.Expression;
import carpet.script.Token;
import carpet.script.external.Carpet;
import carpet.script.value.FunctionValue;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/* The expression evaluators exception class. */
public class ExpressionException extends StacklessRuntimeException implements ResolvedException
{
    public final Context context;
    public final Token token;
    public final List<FunctionValue> stack = new ArrayList<>();
    private final Supplier<String> lazyMessage;
    private String cachedMessage = null;

    public static void prepareForDoom()
    {
        Carpet.Messenger_compose("foo bar");
    }

    public ExpressionException(Context c, Expression e, String message)
    {
        this(c, e, Token.NONE, message);
    }

    public ExpressionException(Context c, Expression e, Token t, String message)
    {
        this(c, e, t, message, Collections.emptyList());
    }

    public ExpressionException(Context c, Expression e, Token t, String message, List<FunctionValue> stack)
    {
        super("Error");
        this.stack.addAll(stack);
        lazyMessage = () -> makeMessage(c, e, t, message);
        token = t;
        context = c;
    }

    public ExpressionException(Context c, Expression e, Token t, Supplier<String> messageSupplier, List<FunctionValue> stack)
    {
        super("Error");
        this.stack.addAll(stack);
        lazyMessage = () -> makeMessage(c, e, t, messageSupplier.get());
        token = t;
        context = c;
    }

    private static List<String> makeError(Expression expr, @Nullable Token token, String errmessage)
    {
        List<String> errMsg = new ArrayList<>();
        errmessage += expr.getModuleName() == null ? "" : (" in " + expr.getModuleName());
        if (token != null)
        {
            List<String> snippet = expr.getExpressionSnippet(token);
            errMsg.addAll(snippet);

            if (snippet.size() != 1)
            {
                errmessage += " at line " + (token.lineno + 1) + ", pos " + (token.linepos + 1);
            }
            else
            {
                errmessage += " at pos " + (token.pos + 1);
            }
        }
        errMsg.add(errmessage);
        return errMsg;
    }

    static synchronized String makeMessage(Context c, Expression e, Token t, String message) throws ExpressionException
    {
        if (c.getErrorSnooper() != null)
        {
            List<String> alternative = c.getErrorSnooper().apply(e, t, c, message);
            if (alternative != null)
            {
                return String.join("\n", alternative);
            }
        }
        return String.join("\n", makeError(e, t, message));
    }

    @Override
    public String getMessage()
    {
        if (cachedMessage == null)
        {
            cachedMessage = lazyMessage.get();
        }
        return cachedMessage;
    }
}
