package droidkit.processor.sqlite;

import com.squareup.javapoet.*;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import droidkit.annotation.SQLiteObject;
import droidkit.processor.ElementScanner;
import droidkit.processor.ProcessingEnv;
import droidkit.processor.Strings;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementScanner7;
import javax.tools.JavaFileObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.logging.Logger;

/**
 * @author Daniel Serdyukov
 */
public class SQLiteObjectScanner extends ElementScanner {

    private static final CodeBlock.Builder META_BLOCK = CodeBlock.builder();

    private final List<String> mColumnsDef = new ArrayList<>();

    private final List<Func0<String>> mIndices = new ArrayList<>();

    private final List<Func0<String>> mCreateRelations = new ArrayList<>();

    private final List<Func0<String>> mDropRelations = new ArrayList<>();

    private final Map<String, String> mFieldToColumn = new LinkedHashMap<>();

    private final List<Action1<CodeBlock.Builder>> mInstantiateActions = new ArrayList<>();

    private final List<Action1<CodeBlock.Builder>> mSaveActions = new ArrayList<>();

    private final Map<String, Action1<ExecutableElement>> mSetterActions = new LinkedHashMap<>();

    private final String mTableName;

    private final boolean mActiveRecord;

    private final List<String> mUniqueOn;

    private Func0<String> mPrimaryKey;

    public SQLiteObjectScanner(ProcessingEnv env, TypeElement originType) {
        super(env, originType);
        final SQLiteObject annotation = originType.getAnnotation(SQLiteObject.class);
        mTableName = annotation.value();
        mActiveRecord = annotation.activeRecord();
        mUniqueOn = Arrays.asList(annotation.uniqueOn());
    }

    public static void brewMetaClass(ProcessingEnv env) {
        final TypeSpec typeSpec = TypeSpec.classBuilder("SQLiteMetaData")
                .addAnnotation(ClassName.get("android.support.annotation", "Keep"))
                .addModifiers(Modifier.PUBLIC)
                .addStaticBlock(META_BLOCK.build())
                .build();
        final JavaFile javaFile = JavaFile.builder("droidkit.sqlite", typeSpec)
                .addFileComment(AUTO_GENERATED_FILE)
                .build();
        try {
            final JavaFileObject sourceFile = env.createSourceFile(javaFile.packageName + "." + typeSpec.name);
            try (final Writer writer = new BufferedWriter(sourceFile.openWriter())) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            Logger.getGlobal().throwing(SQLiteObjectScanner.class.getName(), "brewMetaClass", e);
        }
    }

    @Override
    protected void scan() {
        getOrigin().accept(new FieldScanner(), null);
        getOrigin().accept(new RelationScanner(), null);
        getOrigin().accept(new MethodScanner(), null);
        if (!mUniqueOn.isEmpty()) {
            mColumnsDef.add("UNIQUE(" + Strings.join(", ", mUniqueOn) + ")");
        }
        brewJava();
    }

    String getPackageName() {
        return getOrigin().getEnclosingElement().toString();
    }

    String getClassName() {
        return getOrigin().getSimpleName() + "$SQLiteHelper";
    }

    String getTableName() {
        return mTableName;
    }

    Func0<String> getPrimaryKey() {
        return mPrimaryKey;
    }

    void setPrimaryKey(final String primaryKey) {
        mPrimaryKey = new Func0<String>() {
            @Override
            public String call() {
                return primaryKey;
            }
        };
    }

    void addColumnDef(String def) {
        mColumnsDef.add(def);
    }

    void putFieldToColumn(String fieldName, String columnName) {
        mFieldToColumn.put(fieldName, columnName);
    }

    void setterAction(String methodName, Action1<ExecutableElement> action) {
        mSetterActions.put(methodName, action);
    }

    void instantiateAction(Action1<CodeBlock.Builder> action) {
        mInstantiateActions.add(action);
    }

    void saveAction(Action1<CodeBlock.Builder> action) {
        mSaveActions.add(action);
    }

    void createIndex(Func0<String> index) {
        mIndices.add(index);
    }

    void createRelation(Func0<String> index) {
        mCreateRelations.add(index);
    }

    void dropRelation(Func0<String> index) {
        mDropRelations.add(index);
    }

    //region implementation
    private ClassName brewJava() {
        final TypeSpec typeSpec = TypeSpec.classBuilder(getClassName())
                .addAnnotation(ClassName.get("android.support.annotation", "Keep"))
                .addModifiers(Modifier.PUBLIC)
                .addField(clientRef())
                .addMethod(createTable())
                .addMethod(createIndices())
                .addMethod(createRelationTables())
                .addMethod(createTriggers())
                .addMethod(dropTable())
                .addMethod(dropRelationTables())
                .addMethod(attachInfo())
                .addMethod(instantiate())
                .addMethod(save())
                .addMethod(updateWithClient())
                .addMethod(updateIfActive())
                .addMethod(remove())
                .addOriginatingElement(getOrigin())
                .build();
        final JavaFile javaFile = JavaFile.builder(getPackageName(), typeSpec)
                .addFileComment(AUTO_GENERATED_FILE)
                .build();
        try {
            final JavaFileObject sourceFile = getEnv().createSourceFile(
                    javaFile.packageName + "." + typeSpec.name, getOrigin());
            try (final Writer writer = new BufferedWriter(sourceFile.openWriter())) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            Logger.getGlobal().throwing(SQLiteObjectScanner.class.getName(), "brewJava", e);
        }
        attachHelperToProvider(javaFile, typeSpec);
        attachTableInfoToSchema(javaFile, typeSpec);
        return ClassName.get(javaFile.packageName, typeSpec.name);
    }

    private FieldSpec clientRef() {
        return FieldSpec.builder(ParameterizedTypeName.get(
                ClassName.get(Reference.class),
                ClassName.get("droidkit.sqlite", "SQLiteClient")
        ), "sClientRef", Modifier.PRIVATE, Modifier.STATIC).build();
    }

    private MethodSpec attachInfo() {
        return MethodSpec.methodBuilder("attachInfo")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteClient"), "client")
                .addStatement("sClientRef = new $T<>(client)", ClassName.get(WeakReference.class))
                .build();
    }

    private MethodSpec createTable() {
        return MethodSpec.methodBuilder("createTable")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteDb"), "db")
                .addStatement("db.compileStatement($S).execute()", "CREATE TABLE IF NOT EXISTS " + mTableName +
                        "(" + Strings.join(", ", mColumnsDef) + ");")
                .build();
    }

    private MethodSpec createIndices() {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("createIndices")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteDb"), "db");
        for (final Func0<String> index : mIndices) {
            builder.addStatement("db.compileStatement($S).execute()", index.call());
        }
        return builder.build();
    }

    private MethodSpec createRelationTables() {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("createRelationTables")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteDb"), "db");
        for (final Func0<String> index : mCreateRelations) {
            builder.addStatement("db.compileStatement($S).execute()", index.call());
        }
        return builder.build();
    }

    private MethodSpec createTriggers() {
        return MethodSpec.methodBuilder("createTriggers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteDb"), "db")
                .build();
    }

    private MethodSpec dropTable() {
        return MethodSpec.methodBuilder("dropTable")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteDb"), "db")
                .addStatement("db.compileStatement($S).execute()", "DROP TABLE IF EXISTS " + mTableName + ";")
                .build();
    }

    private MethodSpec dropRelationTables() {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("dropRelationTables")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteDb"), "db");
        for (final Func0<String> index : mDropRelations) {
            builder.addStatement("db.compileStatement($S).execute()", index.call());
        }
        return builder.build();
    }

    private MethodSpec instantiate() {
        final CodeBlock.Builder statements = CodeBlock.builder();
        for (final Action1<CodeBlock.Builder> action : mInstantiateActions) {
            action.call(statements);
        }
        return MethodSpec.methodBuilder("instantiate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("android.database", "Cursor"), "cursor")
                .returns(ClassName.get(getOrigin()))
                .addStatement("final $1T object = new $1T()", ClassName.get(getOrigin()))
                .addCode(statements.build())
                .addStatement("return object")
                .build();
    }

    private MethodSpec save() {
        final CodeBlock.Builder saveActions = CodeBlock.builder();
        for (final Action1<CodeBlock.Builder> action : mSaveActions) {
            action.call(saveActions);
        }
        return MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteClient"), "client")
                .addParameter(ClassName.get(getOrigin()), "object")
                .returns(TypeName.LONG)
                .beginControlFlow("if (object.$L > 0)", getPk())
                .addStatement("object.$1L = client.executeInsert($2S, object.$1L, $3L)", getPk(),
                        String.format(Locale.US, "INSERT INTO %s(_id, %s) VALUES(?, %s);", mTableName,
                                Strings.join(", ", mFieldToColumn.values()),
                                Strings.join(", ", Collections.nCopies(mFieldToColumn.size(), "?"))),
                        Strings.transformAndJoin(", ", mFieldToColumn.keySet(), new ObjectField()))
                .nextControlFlow("else")
                .addStatement("object.$L = client.executeInsert($S, $L)", getPk(),
                        String.format(Locale.US, "INSERT INTO %s(%s) VALUES(%s);", mTableName,
                                Strings.join(", ", mFieldToColumn.values()),
                                Strings.join(", ", Collections.nCopies(mFieldToColumn.size(), "?"))),
                        Strings.transformAndJoin(", ", mFieldToColumn.keySet(), new ObjectField()))
                .endControlFlow()
                .addCode(saveActions.build())
                .addStatement("$T.notifyChange($T.class)",
                        ClassName.get("droidkit.sqlite", "SQLiteSchema"),
                        ClassName.get(getOrigin()))
                .addStatement("return object.$L", getPk())
                .build();
    }

    private MethodSpec updateWithClient() {
        return MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteClient"), "client")
                .addParameter(ClassName.get(getOrigin()), "object")
                .returns(TypeName.INT)
                .addStatement("$T affectedRows = client.executeUpdateDelete($S, $L, $L)",
                        TypeName.INT, "UPDATE " + mTableName + " SET " +
                                Strings.transformAndJoin(", ", mFieldToColumn.values(), new ColumnBinder()) +
                                " WHERE _id = ?;",
                        Strings.transformAndJoin(", ", mFieldToColumn.keySet(), new ObjectField()),
                        "object." + getPk())
                .beginControlFlow("if (affectedRows > 0)")
                .addStatement("$T.notifyChange($T.class)",
                        ClassName.get("droidkit.sqlite", "SQLiteSchema"),
                        ClassName.get(getOrigin()))
                .endControlFlow()
                .addStatement("return affectedRows")
                .build();
    }

    private MethodSpec remove() {
        return MethodSpec.methodBuilder("remove")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("droidkit.sqlite", "SQLiteClient"), "client")
                .addParameter(ClassName.get(getOrigin()), "object")
                .returns(TypeName.INT)
                .addStatement("$T affectedRows = client.executeUpdateDelete($S, $L)", TypeName.INT,
                        "DELETE FROM " + mTableName + " WHERE _id = ?;", "object." + getPk())
                .beginControlFlow("if (affectedRows > 0)")
                .addStatement("$T.notifyChange($T.class)",
                        ClassName.get("droidkit.sqlite", "SQLiteSchema"),
                        ClassName.get(getOrigin()))
                .endControlFlow()
                .addStatement("return affectedRows")
                .build();
    }

    private MethodSpec updateIfActive() {
        return MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.STATIC)
                .addParameter(String.class, "column")
                .addParameter(Object.class, "value")
                .addParameter(TypeName.LONG, "rowId")
                .returns(TypeName.INT)
                .addStatement("$T affectedRows = 0", TypeName.INT)
                .beginControlFlow("if (rowId > 0 && sClientRef != null)")
                .addStatement("final $T client = sClientRef.get()", ClassName.get("droidkit.sqlite", "SQLiteClient"))
                .beginControlFlow("if (client != null)")
                .addStatement("affectedRows = client.executeUpdateDelete(\"UPDATE $L SET \" + column + \" = ?" +
                        " WHERE _id = ?;\", value, rowId)", mTableName)
                .beginControlFlow("if (affectedRows > 0)")
                .addStatement("$T.notifyChange($T.class)",
                        ClassName.get("droidkit.sqlite", "SQLiteSchema"),
                        ClassName.get(getOrigin()))
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("return affectedRows")
                .build();
    }

    private void attachHelperToProvider(JavaFile javaFile, TypeSpec typeSpec) {
        if (mActiveRecord) {
            META_BLOCK.addStatement("$T.attachHelper($T.class)",
                    ClassName.get("droidkit.sqlite", "SQLiteProvider"),
                    ClassName.get(javaFile.packageName, typeSpec.name));
        }
    }

    private void attachTableInfoToSchema(JavaFile javaFile, TypeSpec typeSpec) {
        META_BLOCK.addStatement("$T.attachTableInfo($T.class, $S, $T.class)",
                ClassName.get("droidkit.sqlite", "SQLiteSchema"),
                ClassName.get(getOrigin()), mTableName,
                ClassName.get(javaFile.packageName, typeSpec.name));
    }
    //endregion

    private String getPk() {
        return getPrimaryKey().call();
    }

    //region reactive functions
    private static class ColumnBinder implements Func1<String, String> {

        @Override
        public String call(String column) {
            return column + " = ?";
        }

    }

    private static class ObjectField implements Func1<String, String> {

        @Override
        public String call(String field) {
            return "object." + field;
        }

    }
    //endregion

    //region scanners
    private class FieldScanner extends ElementScanner7<Void, Void> {

        @Override
        public Void visitVariable(VariableElement field, Void aVoid) {
            for (final FieldVisitor visitor : FieldVisitor.SUPPORTED) {
                final Annotation annotation = visitor.getAnnotation(getEnv(), field);
                if (annotation != null) {
                    getEnv().<JCTree.JCVariableDecl>getTree(field).mods.flags &= ~Flags.PRIVATE;
                    visitor.visit(SQLiteObjectScanner.this, getEnv(), field, annotation);
                }
            }
            return super.visitVariable(field, aVoid);
        }

    }

    private class RelationScanner extends ElementScanner7<Void, Void> {

        @Override
        public Void visitVariable(VariableElement field, Void aVoid) {
            final SQLiteRelationVisitor visitor = new SQLiteRelationVisitor();
            final Annotation annotation = visitor.getAnnotation(getEnv(), field);
            if (annotation != null) {
                getEnv().<JCTree.JCVariableDecl>getTree(field).mods.flags &= ~Flags.PRIVATE;
                visitor.visit(SQLiteObjectScanner.this, getEnv(), field, annotation);
            }
            return super.visitVariable(field, aVoid);
        }

    }

    private class MethodScanner extends ElementScanner7<Void, Void> {

        @Override
        public Void visitExecutable(ExecutableElement method, Void aVoid) {
            if (mActiveRecord && getOrigin().equals(method.getEnclosingElement())) {
                final String methodName = method.getSimpleName().toString();
                final Action1<ExecutableElement> action = mSetterActions.get(methodName);
                if (action != null) {
                    action.call(method);
                }
            }
            return super.visitExecutable(method, aVoid);
        }

    }
    //endregion

}
