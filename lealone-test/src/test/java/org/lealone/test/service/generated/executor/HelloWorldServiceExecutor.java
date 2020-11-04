package org.lealone.test.service.generated.executor;

import java.sql.Date;
import org.lealone.db.service.ServiceExecutor;
import org.lealone.db.value.*;
import org.lealone.orm.json.JsonArray;
import org.lealone.test.service.impl.HelloWorldServiceImpl;

/**
 * Service executor for 'hello_world_service'.
 *
 * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.
 */
public class HelloWorldServiceExecutor implements ServiceExecutor {

    private final HelloWorldServiceImpl s = new HelloWorldServiceImpl();

    public HelloWorldServiceExecutor() {
    }

    @Override
    public String executeService(String methodName, String json) {
        JsonArray ja = null;
        switch (methodName) {
        case "SAY_HELLO":
            this.s.sayHello();
            break;
        case "GET_DATE":
            Date result2 = this.s.getDate();
            if (result2 == null)
                return null;
            return result2.toString();
        case "GET_INT":
            Integer result3 = this.s.getInt();
            if (result3 == null)
                return null;
            return result3.toString();
        case "GET_TWO":
            ja = new JsonArray(json);
            String p_name4 = ja.getString(0);
            Integer p_age4 = Integer.valueOf(ja.getValue(1).toString());
            Integer result4 = this.s.getTwo(p_name4, p_age4);
            if (result4 == null)
                return null;
            return result4.toString();
        case "SAY_GOODBYE_TO":
            ja = new JsonArray(json);
            String p_name5 = ja.getString(0);
            String result5 = this.s.sayGoodbyeTo(p_name5);
            if (result5 == null)
                return null;
            return result5;
        default:
            throw new RuntimeException("no method: " + methodName);
        }
        return NO_RETURN_VALUE;
    }

    @Override
    public Value executeService(String methodName, Value[] methodArgs) {
        switch (methodName) {
        case "SAY_HELLO":
            this.s.sayHello();
            break;
        case "GET_DATE":
            Date result2 = this.s.getDate();
            if (result2 == null)
                return ValueNull.INSTANCE;
            return ValueDate.get(result2);
        case "GET_INT":
            Integer result3 = this.s.getInt();
            if (result3 == null)
                return ValueNull.INSTANCE;
            return ValueInt.get(result3);
        case "GET_TWO":
            String p_name4 = methodArgs[0].getString();
            Integer p_age4 = methodArgs[1].getInt();
            Integer result4 = this.s.getTwo(p_name4, p_age4);
            if (result4 == null)
                return ValueNull.INSTANCE;
            return ValueInt.get(result4);
        case "SAY_GOODBYE_TO":
            String p_name5 = methodArgs[0].getString();
            String result5 = this.s.sayGoodbyeTo(p_name5);
            if (result5 == null)
                return ValueNull.INSTANCE;
            return ValueString.get(result5);
        default:
            throw new RuntimeException("no method: " + methodName);
        }
        return ValueNull.INSTANCE;
    }
}
