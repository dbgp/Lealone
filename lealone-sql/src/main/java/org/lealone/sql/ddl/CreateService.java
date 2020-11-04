/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.sql.ddl;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.UUID;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.CamelCaseHelper;
import org.lealone.db.DbObjectType;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.lock.DbObjectLock;
import org.lealone.db.schema.Schema;
import org.lealone.db.service.Service;
import org.lealone.db.service.ServiceExecutor;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Column;
import org.lealone.db.table.CreateTableData;
import org.lealone.db.value.DataType;
import org.lealone.db.value.Value;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * CREATE SERVICE
 * 
 * @author zhh
 */
public class CreateService extends SchemaStatement {

    private final ArrayList<CreateTable> serviceMethods = new ArrayList<>();
    private String serviceName;
    private boolean ifNotExists;
    private String comment;
    private String packageName;
    private String implementBy;
    private boolean genCode;
    private String codePath;

    public CreateService(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_SERVICE;
    }

    @Override
    public boolean isReplicationStatement() {
        return true;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void addServiceMethod(CreateTable serviceMethod) {
        serviceMethods.add(serviceMethod);
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setImplementBy(String implementBy) {
        this.implementBy = implementBy;
    }

    public void setGenCode(boolean genCode) {
        this.genCode = genCode;
    }

    public void setCodePath(String codePath) {
        this.codePath = codePath;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.SERVICE, session);
        if (lock == null)
            return -1;

        if (schema.findService(session, serviceName) != null) {
            if (ifNotExists) {
                return 0;
            }
            throw DbException.get(ErrorCode.SERVICE_ALREADY_EXISTS_1, serviceName);
        }
        int id = getObjectId();
        Service service = new Service(schema, id, serviceName, sql, getExecutorFullName());
        service.setImplementBy(implementBy);
        service.setPackageName(packageName);
        service.setComment(comment);
        schema.add(session, service, lock);
        // 数据库在启动阶段执行CREATE SERVICE语句时不用再生成代码
        if (genCode && !session.getDatabase().isStarting())
            genCode();
        return 0;
    }

    public static String toClassName(String n) {
        n = CamelCaseHelper.toCamelFromUnderscore(n);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String toMethodName(String n) {
        return CamelCaseHelper.toCamelFromUnderscore(n);
    }

    private static String toFieldName(String n) {
        return CamelCaseHelper.toCamelFromUnderscore(n);
    }

    private void genCode() {
        genServiceInterfaceCode();
        genServiceExecutorCode();
    }

    private void genServiceInterfaceCode() {
        StringBuilder buff = new StringBuilder();
        StringBuilder ibuff = new StringBuilder();
        StringBuilder proxyMethodsBuff = new StringBuilder();
        StringBuilder proxyMethodsBuffJdbc = new StringBuilder();

        StringBuilder psBuff = new StringBuilder();
        StringBuilder psInitBuff = new StringBuilder();

        TreeSet<String> importSet = new TreeSet<>();
        importSet.add("org.lealone.orm.json.JsonArray");
        importSet.add("org.lealone.client.ClientServiceProxy");
        importSet.add("java.sql.*");

        String serviceName = toClassName(this.serviceName);

        buff.append("public interface ").append(serviceName).append(" {\r\n");
        buff.append("\r\n");
        buff.append("    static ").append(serviceName).append(" create(String url) {\r\n");
        buff.append("        if (new org.lealone.db.ConnectionInfo(url).isEmbedded())\r\n");
        buff.append("            return new ").append(getServiceImplementClassName()).append("();\r\n");
        buff.append("        else;\r\n");
        buff.append("            return new JdbcProxy(url);\r\n");
        buff.append("    }\r\n");

        int methodIndex = 0;
        for (CreateTable m : serviceMethods) {
            methodIndex++;
            String psVarName = "ps" + methodIndex;
            CreateTableData data = m.data;
            buff.append("\r\n");
            proxyMethodsBuff.append("\r\n");
            proxyMethodsBuffJdbc.append("\r\n");

            psBuff.append("        private final PreparedStatement ").append(psVarName).append(";\r\n");
            psInitBuff.append("            ").append(psVarName)
                    .append(" = ClientServiceProxy.prepareStatement(url, \"EXECUTE SERVICE ").append(this.serviceName)
                    .append(" ").append(data.tableName).append("(");
            for (int i = 0, size = data.columns.size() - 1; i < size; i++) {
                if (i != 0) {
                    psInitBuff.append(", ");
                }
                psInitBuff.append("?");
            }
            psInitBuff.append(")\");\r\n");

            Column returnColumn = data.columns.get(data.columns.size() - 1);
            String returnType = getTypeName(returnColumn, importSet);
            String methodName = toMethodName(data.tableName);
            buff.append("    ").append(returnType).append(" ").append(methodName).append("(");

            proxyMethodsBuff.append("        @Override\r\n");
            proxyMethodsBuff.append("        public ").append(returnType).append(" ").append(methodName).append("(");

            proxyMethodsBuffJdbc.append("        @Override\r\n");
            proxyMethodsBuffJdbc.append("        public ").append(returnType).append(" ").append(methodName)
                    .append("(");

            StringBuilder argsBuff = new StringBuilder();
            StringBuilder argsBuffJdbc = new StringBuilder();

            argsBuff.append("            JsonArray ja = new JsonArray();\r\n");
            for (int i = 0, size = data.columns.size() - 1; i < size; i++) {
                if (i != 0) {
                    buff.append(", ");
                    proxyMethodsBuff.append(", ");
                    proxyMethodsBuffJdbc.append(", ");
                }
                Column c = data.columns.get(i);
                String cType = getTypeName(c, importSet);
                String cName = toFieldName(c.getName());
                buff.append(cType).append(" ").append(cName);
                proxyMethodsBuff.append(cType).append(" ").append(cName);
                proxyMethodsBuffJdbc.append(cType).append(" ").append(cName);
                if (c.getTable() != null) {
                    importSet.add("org.lealone.orm.json.JsonObject");
                    argsBuff.append("            ja.add(JsonObject.mapFrom(").append(cName).append("));\r\n");
                } else {
                    argsBuff.append("            ja.add(").append(cName).append(");\r\n");

                    argsBuffJdbc.append("                ").append(psVarName).append(".")
                            .append(getPreparedStatementSetterMethodName(cType)).append("(").append(i + 1).append(", ")
                            .append(cName).append(");\r\n");
                }
            }
            buff.append(");\r\n");
            proxyMethodsBuff.append(") {\r\n");
            proxyMethodsBuff.append(argsBuff);
            if (returnType.equalsIgnoreCase("void")) {
                proxyMethodsBuff.append("            ClientServiceProxy.executeNoReturnValue(url, \"")
                        .append(this.serviceName).append('.').append(data.tableName).append("\", ja.encode());\r\n");
            } else {
                proxyMethodsBuff.append("            String result = ClientServiceProxy.executeWithReturnValue(url, \"")
                        .append(this.serviceName).append('.').append(data.tableName).append("\", ja.encode());\r\n");
                proxyMethodsBuff.append("            if (result != null) {\r\n");
                if (returnColumn.getTable() != null) {
                    importSet.add("org.lealone.orm.json.JsonObject");
                    proxyMethodsBuff.append("                JsonObject jo = new JsonObject(result);\r\n");
                    proxyMethodsBuff.append("                return jo.mapTo(").append(returnType)
                            .append(".class);\r\n");
                } else {
                    proxyMethodsBuff.append("                return ").append(getResultMethodName(returnType))
                            .append(";\r\n");
                }
                proxyMethodsBuff.append("            }\r\n");
                proxyMethodsBuff.append("            return null;\r\n");
            }
            proxyMethodsBuff.append("        }\r\n");

            proxyMethodsBuffJdbc.append(") {\r\n");
            proxyMethodsBuffJdbc.append("            try {\r\n");
            proxyMethodsBuffJdbc.append(argsBuffJdbc);
            if (returnType.equalsIgnoreCase("void")) {
                proxyMethodsBuffJdbc.append("                ").append(psVarName).append(".executeUpdate();\r\n");
            } else {
                proxyMethodsBuffJdbc.append("                ResultSet rs = ").append(psVarName)
                        .append(".executeQuery();\r\n");
                proxyMethodsBuffJdbc.append("                rs.next();\r\n");

                if (returnColumn.getTable() != null) {
                    importSet.add("org.lealone.orm.json.JsonObject");
                    proxyMethodsBuffJdbc.append("                JsonObject jo = new JsonObject(result);\r\n");
                    proxyMethodsBuffJdbc.append("                return jo.mapTo(").append(returnType)
                            .append(".class);\r\n");
                } else {
                    proxyMethodsBuffJdbc.append("                ").append(returnType).append(" ret = rs.")
                            .append(getResultSetReturnMethodName(returnType)).append("(1);\r\n");
                    proxyMethodsBuffJdbc.append("                rs.close();\r\n");
                    proxyMethodsBuffJdbc.append("                return ret;\r\n");
                }
            }
            proxyMethodsBuffJdbc.append("            } catch (Throwable e) {\r\n");
            proxyMethodsBuffJdbc.append("                throw ClientServiceProxy.failed(\"").append(this.serviceName)
                    .append('.').append(data.tableName).append("\", e);\r\n");
            proxyMethodsBuffJdbc.append("            }\r\n");
            proxyMethodsBuffJdbc.append("        }\r\n");
        }

        // 生成Proxy类
        buff.append("\r\n");
        buff.append("    static class Proxy implements ").append(serviceName).append(" {\r\n");
        buff.append("\r\n");
        buff.append("        private final String url;\r\n");
        buff.append("\r\n");
        buff.append("        private Proxy(String url) {\r\n");
        buff.append("            this.url = url;\r\n");
        buff.append("        }\r\n");
        buff.append(proxyMethodsBuff);
        buff.append("    }\r\n");

        // 生成Jdbc Proxy类
        buff.append("\r\n");
        buff.append("    static class JdbcProxy implements ").append(serviceName).append(" {\r\n");
        buff.append("\r\n");
        buff.append(psBuff);
        buff.append("\r\n");
        buff.append("        private JdbcProxy(String url) {\r\n");
        buff.append(psInitBuff);
        buff.append("        }\r\n");
        buff.append(proxyMethodsBuffJdbc);
        buff.append("    }\r\n");
        buff.append("}\r\n");

        ibuff.append("package ").append(packageName).append(";\r\n");
        ibuff.append("\r\n");
        for (String i : importSet) {
            ibuff.append("import ").append(i).append(";\r\n");
        }
        ibuff.append("\r\n");

        ibuff.append("/**\r\n");
        ibuff.append(" * Service interface for '").append(this.serviceName.toLowerCase()).append("'.\r\n");
        ibuff.append(" *\r\n");
        ibuff.append(" * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.\r\n");
        ibuff.append(" */\r\n");

        writeFile(codePath, packageName, serviceName, ibuff, buff);
    }

    private String getServiceImplementClassName() {
        return implementBy;
    }

    private void genServiceExecutorCode() {
        StringBuilder buff = new StringBuilder();
        StringBuilder ibuff = new StringBuilder();

        TreeSet<String> importSet = new TreeSet<>();
        importSet.add(ServiceExecutor.class.getName());
        String serviceImplementClassName = implementBy;
        if (implementBy != null) {
            if (implementBy.startsWith(packageName)) {
                serviceImplementClassName = implementBy.substring(packageName.length() + 1);
            } else {
                int lastDotPos = implementBy.lastIndexOf('.');
                if (lastDotPos > 0) {
                    serviceImplementClassName = implementBy.substring(lastDotPos + 1);
                    importSet.add(implementBy);
                }
            }
        }
        String className = getExecutorSimpleName();

        buff.append("public class ").append(className).append(" implements ServiceExecutor {\r\n");
        buff.append("\r\n");
        buff.append("    private final ").append(serviceImplementClassName).append(" s = new ")
                .append(serviceImplementClassName).append("();\r\n");
        buff.append("\r\n");
        buff.append("    public ").append(className).append("() {\r\n");
        buff.append("    }\r\n");
        buff.append("\r\n");
        buff.append("    @Override\r\n");
        buff.append("    public String executeService(String methodName, String json) {\r\n");
        // 提前看一下是否用到JsonArray
        for (CreateTable m : serviceMethods) {
            if (m.data.columns.size() - 1 > 0) {
                buff.append("        JsonArray ja = null;\r\n");
                break;
            }
        }
        buff.append("        switch (methodName) {\r\n");

        boolean hasNoReturnValueMethods = false;
        int index = 0;
        for (CreateTable m : serviceMethods) {
            index++;
            // switch语句不同case代码块的本地变量名不能相同
            String resultVarName = "result" + index;
            CreateTableData data = m.data;

            Column returnColumn = data.columns.get(data.columns.size() - 1);
            String returnType = getTypeName(returnColumn, importSet);
            if (returnType.equalsIgnoreCase("void")) {
                hasNoReturnValueMethods = true;
            }
            StringBuilder argsBuff = new StringBuilder();
            String methodName = toMethodName(data.tableName);
            buff.append("        case \"").append(data.tableName).append("\":\r\n");
            // 有参数，参数放在一个json数组中
            int size = data.columns.size() - 1;
            if (size > 0) {
                importSet.add("org.lealone.orm.json.JsonArray");
                buff.append("            ja = new JsonArray(json);\r\n");
                for (int i = 0; i < size; i++) {
                    if (i != 0) {
                        argsBuff.append(", ");
                    }
                    Column c = data.columns.get(i);
                    String cType = getTypeName(c, importSet);
                    String cName = "p_" + toFieldName(c.getName()) + index;
                    buff.append("            ").append(cType).append(" ").append(cName).append(" = ")
                            .append(getJsonArrayMethodName(cType, i)).append(";\r\n");
                    argsBuff.append(cName);
                }
            }
            boolean isVoid = returnType.equalsIgnoreCase("void");
            buff.append("            ");
            if (!isVoid) {
                buff.append(returnType).append(" ").append(resultVarName).append(" = ");
            }
            buff.append("this.s.").append(methodName).append("(").append(argsBuff).append(");\r\n");
            if (!isVoid) {
                buff.append("            if (").append(resultVarName).append(" == null)\r\n");
                buff.append("                return null;\r\n");
                if (returnColumn.getTable() != null) {
                    importSet.add("org.lealone.orm.json.JsonObject");
                    buff.append("            return JsonObject.mapFrom(").append(resultVarName)
                            .append(").encode();\r\n");
                } else if (!returnType.equalsIgnoreCase("string")) {
                    buff.append("            return ").append(resultVarName).append(".toString();\r\n");
                } else {
                    buff.append("            return ").append(resultVarName).append(";\r\n");
                }
            } else {
                buff.append("            break;\r\n");
            }
        }
        buff.append("        default:\r\n");
        buff.append("            throw new RuntimeException(\"no method: \" + methodName);\r\n");
        buff.append("        }\r\n");
        if (hasNoReturnValueMethods)
            buff.append("        return NO_RETURN_VALUE;\r\n");

        buff.append("    }\r\n");

        // 生成public Value executeService(String methodName, Value[] methodArgs)方法
        // importSet.add(Value.class.getName());
        // importSet.add(ValueNull.class.getName());
        importSet.add("org.lealone.db.value.*");
        buff.append("\r\n");
        buff.append("    @Override\r\n");
        buff.append("    public Value executeService(String methodName, Value[] methodArgs) {\r\n");
        buff.append("        switch (methodName) {\r\n");

        hasNoReturnValueMethods = false;
        index = 0;
        for (CreateTable m : serviceMethods) {
            index++;
            // switch语句不同case代码块的本地变量名不能相同
            String resultVarName = "result" + index;
            CreateTableData data = m.data;

            Column returnColumn = data.columns.get(data.columns.size() - 1);
            String returnType = getTypeName(returnColumn, importSet);
            if (returnType.equalsIgnoreCase("void")) {
                hasNoReturnValueMethods = true;
            }
            StringBuilder argsBuff = new StringBuilder();
            String methodName = toMethodName(data.tableName);
            buff.append("        case \"").append(data.tableName).append("\":\r\n");
            // 有参数，参数放在一个json数组中
            int size = data.columns.size() - 1;
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    if (i != 0) {
                        argsBuff.append(", ");
                    }
                    Column c = data.columns.get(i);
                    String cType = getTypeName(c, importSet);
                    String cName = "p_" + toFieldName(c.getName()) + index;
                    buff.append("            ").append(cType).append(" ").append(cName).append(" = ")
                            .append("methodArgs[").append(i).append("].").append(getValueMethodName(cType))
                            .append("();\r\n");
                    argsBuff.append(cName);
                }
            }
            boolean isVoid = returnType.equalsIgnoreCase("void");
            buff.append("            ");
            if (!isVoid) {
                buff.append(returnType).append(" ").append(resultVarName).append(" = ");
            }
            buff.append("this.s.").append(methodName).append("(").append(argsBuff).append(");\r\n");
            if (!isVoid) {
                buff.append("            if (").append(resultVarName).append(" == null)\r\n");
                buff.append("                return ValueNull.INSTANCE;\r\n");
                buff.append("            return ")// .append("org.lealone.db.value.")
                        .append(getReturnMethodName(returnType)).append("(").append(resultVarName).append(")")
                        .append(";\r\n");
            } else {
                buff.append("            break;\r\n");
            }
        }
        buff.append("        default:\r\n");
        buff.append("            throw new RuntimeException(\"no method: \" + methodName);\r\n");
        buff.append("        }\r\n");
        if (hasNoReturnValueMethods)
            buff.append("        return ValueNull.INSTANCE;\r\n");

        buff.append("    }\r\n");
        buff.append("}\r\n");

        ibuff.append("package ").append(getExecutorPackageName()).append(";\r\n");
        ibuff.append("\r\n");
        for (String i : importSet) {
            ibuff.append("import ").append(i).append(";\r\n");
        }
        ibuff.append("\r\n");

        ibuff.append("/**\r\n");
        ibuff.append(" * Service executor for '").append(this.serviceName.toLowerCase()).append("'.\r\n");
        ibuff.append(" *\r\n");
        ibuff.append(" * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.\r\n");
        ibuff.append(" */\r\n");

        writeFile(codePath, getExecutorPackageName(), className, ibuff, buff);
    }

    private String getExecutorPackageName() {
        return packageName + ".executor";
    }

    private String getExecutorFullName() {
        return getExecutorPackageName() + "." + getExecutorSimpleName();
    }

    private String getExecutorSimpleName() {
        return toClassName(serviceName) + "Executor";
    }

    public static void writeFile(String codePath, String packageName, String className, StringBuilder... buffArray) {
        String path = codePath;
        if (!path.endsWith(File.separator))
            path = path + File.separator;
        path = path.replace('/', File.separatorChar);
        path = path + packageName.replace('.', File.separatorChar) + File.separatorChar;
        try {
            if (!new File(path).exists()) {
                new File(path).mkdirs();
            }
            Charset utf8 = Charset.forName("UTF-8");
            BufferedOutputStream file = new BufferedOutputStream(new FileOutputStream(path + className + ".java"));
            for (StringBuilder buff : buffArray) {
                file.write(buff.toString().getBytes(utf8));
            }
            file.close();
        } catch (IOException e) {
            throw DbException.convertIOException(e, "Failed to genJavaCode, path = " + path);
        }
    }

    private static String getTypeName(Column c, TreeSet<String> importSet) {
        String cType;
        if (c.getTable() != null) {
            cType = c.getTable().getName();
            cType = toClassName(cType);
            String packageName = c.getTable().getPackageName();
            if (packageName != null)
                cType = packageName + "." + cType;
        } else {
            // cType = c.getOriginalSQL();
            switch (c.getType()) {
            case Value.BYTES:
                cType = "byte[]";
                break;
            case Value.UUID:
                cType = UUID.class.getName();
                break;
            default:
                cType = DataType.getTypeClassName(c.getType());
            }
        }
        int lastDotPos = cType.lastIndexOf('.');
        if (lastDotPos > 0) {
            if (cType.startsWith("java.lang.")) {
                cType = cType.substring(10);
            } else {
                importSet.add(cType);
                cType = cType.substring(lastDotPos + 1);
            }
        }
        // 把java.lang.Void转成void，这样就不用加return语句
        if (cType.equalsIgnoreCase("void")) {
            cType = "void";
        }
        return cType;
    }

    private static String getResultMethodName(String type) {
        type = type.toUpperCase();
        switch (type) {
        case "BOOLEAN":
            return "Boolean.valueOf(result)";
        case "BYTE":
            return "Byte.valueOf(result)";
        case "SHORT":
            return "Short.valueOf(result)";
        case "INTEGER":
            return "Integer.valueOf(result)";
        case "LONG":
            return "Long.valueOf(result)";
        case "DECIMAL":
            return "new java.math.BigDecimal(result)";
        case "TIME":
            return "java.sql.Time.valueOf(result)";
        case "DATE":
            return "java.sql.Date.valueOf(result)";
        case "TIMESTAMP":
            return "java.sql.Timestamp.valueOf(result)";
        case "BYTES":
            // "[B", not "byte[]";
            return "result.getBytes()";
        case "UUID":
            return "java.util.UUID.fromString(result)";
        case "STRING":
        case "STRING_IGNORECASE":
        case "STRING_FIXED":
            return "result";
        case "DOUBLE":
            return "Double.valueOf(result)";
        case "FLOAT":
            return "Float.valueOf(result)";
        case "NULL":
            return null;
        case "UNKNOWN": // anything
        case "JAVA_OBJECT":
            return "(Object)result";
        case "BLOB":
            type = "java.sql.Blob";
            break;
        case "CLOB":
            type = "java.sql.Clob";
            break;
        case "ARRAY":
            type = "java.sql.Array";
            break;
        case "RESULT_SET":
            type = "java.sql.ResultSet";
            break;
        }
        return "(" + type + ")result";
    }

    private static String getReturnMethodName(String type) {
        type = type.toUpperCase();
        switch (type) {
        case "BOOLEAN":
            return "ValueBoolean.get";
        case "BYTE":
            return "ValueByte.get";
        case "SHORT":
            return "ValueShort.get";
        case "INTEGER":
            return "ValueInt.get";
        case "LONG":
            return "ValueLong.get";
        case "DECIMAL":
            return "ValueDecimal.get";
        case "TIME":
            return "ValueTime.get";
        case "DATE":
            return "ValueDate.get";
        case "TIMESTAMP":
            return "ValueTimestamp.get";
        case "BYTES":
            return "ValueBytes.get";
        case "UUID":
            return "ValueUuid.get";
        case "STRING":
        case "STRING_IGNORECASE":
        case "STRING_FIXED":
            return "ValueString.get";
        case "DOUBLE":
            return "ValueDouble.get";
        case "FLOAT":
            return "ValueFloat.get";
        case "NULL":
            return "ValueNull.INSTANCE";
        case "UNKNOWN": // anything
        case "JAVA_OBJECT":
            return "ValueShort.get";
        case "BLOB":
            return "ValueShort.get";
        case "CLOB":
            return "ValueShort.get";
        case "ARRAY":
            return "ValueShort.get";
        case "RESULT_SET":
            return "ValueResultSet.get";
        }
        return "ValueShort.get";
    }

    private static String m(String str, int i) {
        return str + "(ja.getValue(" + i + ").toString())";
    }

    // 根据具体类型调用合适的JsonArray方法
    private static String getJsonArrayMethodName(String type0, int i) {
        String type = type0.toUpperCase();
        switch (type) {
        case "BOOLEAN":
            return m("Boolean.valueOf", i);
        case "BYTE":
            return m("Byte.valueOf", i);
        case "SHORT":
            return m("Short.valueOf", i);
        case "INTEGER":
            return m("Integer.valueOf", i);
        case "LONG":
            return m("Long.valueOf", i);
        case "DECIMAL":
            return m("new java.math.BigDecimal", i);
        case "TIME":
            return m("java.sql.Time.valueOf", i);
        case "DATE":
            return m("java.sql.Date.valueOf", i);
        case "TIMESTAMP":
            return m("java.sql.Timestamp.valueOf", i);
        case "BYTES":
            return "ja.getString(" + i + ").getBytes()";
        case "UUID":
            return m("java.util.UUID.fromString", i);
        case "STRING":
        case "STRING_IGNORECASE":
        case "STRING_FIXED":
            return "ja.getString(" + i + ")";
        case "DOUBLE":
            return m("Double.valueOf", i);
        case "FLOAT":
            return m("Float.valueOf", i);
        case "NULL":
            return null;
        case "UNKNOWN": // anything
        case "JAVA_OBJECT":
            return "ja.getJsonObject(" + i + ")";
        case "BLOB":
            type0 = "java.sql.Blob";
            break;
        case "CLOB":
            type0 = "java.sql.Clob";
            break;
        case "ARRAY":
            type0 = "java.sql.Array";
            break;
        case "RESULT_SET":
            type0 = "java.sql.ResultSet";
            break;
        }
        return "ja.getJsonObject(" + i + ").mapTo(" + type0 + ".class)";
    }

    // 根据具体类型调用合适的Value方法
    private static String getValueMethodName(String type) {
        type = type.toUpperCase();
        switch (type) {
        case "BOOLEAN":
            return "getBoolean";
        case "BYTE":
            return "getByte";
        case "SHORT":
            return "getShort";
        case "INTEGER":
            return "getInt";
        case "LONG":
            return "getLong";
        case "DECIMAL":
            return "getBigDecimal";
        case "TIME":
            return "getTime";
        case "DATE":
            return "getDate";
        case "TIMESTAMP":
            return "getTimestamp";
        case "BYTES":
            return "getBytes";
        case "UUID":
            return "getUuid";
        case "STRING":
        case "STRING_IGNORECASE":
        case "STRING_FIXED":
            return "getString";
        case "DOUBLE":
            return "getDouble";
        case "FLOAT":
            return "getFloat";
        case "NULL":
            return null;
        case "UNKNOWN": // anything
        case "JAVA_OBJECT":
            return "getObject";
        case "BLOB":
            return "getBlob";
        case "CLOB":
            return "getClob";
        case "ARRAY":
            return "getArray";
        case "RESULT_SET":
            return "getResultSet";
        }
        return "getObject";
    } // 根据具体类型调用合适的Value方法

    private static String getResultSetReturnMethodName(String type) {
        type = type.toUpperCase();
        switch (type) {
        case "BOOLEAN":
            return "getBoolean";
        case "BYTE":
            return "getByte";
        case "SHORT":
            return "getShort";
        case "INTEGER":
            return "getInt";
        case "LONG":
            return "getLong";
        case "DECIMAL":
            return "getBigDecimal";
        case "TIME":
            return "getTime";
        case "DATE":
            return "getDate";
        case "TIMESTAMP":
            return "getTimestamp";
        case "BYTES":
            return "getBytes";
        case "UUID":
            return "getUuid"; // TODO
        case "STRING":
        case "STRING_IGNORECASE":
        case "STRING_FIXED":
            return "getString";
        case "DOUBLE":
            return "getDouble";
        case "FLOAT":
            return "getFloat";
        case "NULL":
            return null;
        case "UNKNOWN": // anything
        case "JAVA_OBJECT":
            return "getObject";
        case "BLOB":
            return "getBlob";
        case "CLOB":
            return "getClob";
        case "ARRAY":
            return "getArray";
        case "RESULT_SET":
            return "getResultSet"; // TODO
        }
        return "getObject";
    }

    private static String getPreparedStatementSetterMethodName(String type) {
        type = type.toUpperCase();
        switch (type) {
        case "BOOLEAN":
            return "setBoolean";
        case "BYTE":
            return "setByte";
        case "SHORT":
            return "setShort";
        case "INTEGER":
            return "setInt";
        case "LONG":
            return "setLong";
        case "DECIMAL":
            return "setBigDecimal";
        case "TIME":
            return "setTime";
        case "DATE":
            return "setDate";
        case "TIMESTAMP":
            return "setTimestamp";
        case "BYTES":
            return "setBytes";
        case "UUID":
            return "setUuid"; // TODO
        case "STRING":
        case "STRING_IGNORECASE":
        case "STRING_FIXED":
            return "setString";
        case "DOUBLE":
            return "setDouble";
        case "FLOAT":
            return "setFloat";
        case "NULL":
            return null;
        case "UNKNOWN": // anything
        case "JAVA_OBJECT":
            return "setObject";
        case "BLOB":
            return "setBlob";
        case "CLOB":
            return "setClob";
        case "ARRAY":
            return "setArray";
        case "RESULT_SET":
            return "setResultSet"; // TODO
        }
        return "setObject";
    }
}
