package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import droidkit.annotation.SQLiteObject;
import droidkit.annotation.SQLiteRelation;
import droidkit.processor.ProcessingEnv;
import droidkit.processor.Strings;
import rx.functions.*;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor7;
import javax.lang.model.util.SimpleTypeVisitor7;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @author Daniel Serdyukov
 */
class SQLiteRelationVisitor implements FieldVisitor {

    @Override
    public Annotation getAnnotation(ProcessingEnv env, VariableElement field) {
        return field.getAnnotation(SQLiteRelation.class);
    }

    @Override
    public void visit(SQLiteObjectScanner scanner, final ProcessingEnv env, VariableElement field,
                      Annotation annotation) {
        final SQLiteRelation aRel = field.getAnnotation(SQLiteRelation.class);
        final Relation relation = field.asType().accept(new SimpleTypeVisitor7<Relation, Void>() {
            @Override
            public Relation visitDeclared(DeclaredType t, Void aVoid) {
                if (t.getTypeArguments().isEmpty()) {
                    return t.asElement().accept(new OneToOneVisitor(aRel.setter()), null);
                } else if (Objects.equals(List.class.getName(), t.asElement().toString())) {
                    return t.getTypeArguments().get(0).accept(new OneToManyVisitor(), null);
                }
                return new UnsupportedRelation();
            }
        }, null);
        final String relTable = relation.call(scanner, env, field);
        if (!Strings.isNullOrEmpty(relTable)) {
            final String table = scanner.getTableName();
            scanner.createRelation(new Func0<String>() {
                @Override
                public String call() {
                    return String.format(Locale.US, "CREATE TABLE IF NOT EXISTS %1$s_%2$s(" +
                                    "%1$s_id INTEGER REFERENCES %1$s(_id) ON DELETE CASCADE ON UPDATE CASCADE, " +
                                    "%2$s_id INTEGER REFERENCES %2$s(_id) ON DELETE CASCADE ON UPDATE CASCADE, " +
                                    "UNIQUE (%1$s_id, %2$s_id) ON CONFLICT IGNORE);",
                            table, relTable);
                }
            });
            scanner.dropRelation(new Func0<String>() {
                @Override
                public String call() {
                    return String.format(Locale.US, "DROP TABLE IF EXISTS %s_%s;", table, relTable);
                }
            });
        }
    }

    //region relations
    private interface Relation extends Func3<SQLiteObjectScanner, ProcessingEnv, VariableElement, String> {

    }

    private static class OneToOneRelation implements Relation {

        private final TypeMirror mRelType;

        private final String mRelTypeTable;

        private final String mSetterName;

        public OneToOneRelation(TypeMirror relType, String relTable, String setterName) {
            mRelType = relType;
            mRelTypeTable = relTable;
            mSetterName = setterName;
        }

        @Override
        public String call(final SQLiteObjectScanner scanner, final ProcessingEnv env, final VariableElement field) {
            final String relTable = scanner.getTableName() + "_" + mRelTypeTable;
            final String relQuery = String.format(Locale.US, "SELECT %1$s.* FROM %1$s, %3$s" +
                            " WHERE %1$s._id=%3$s.%1$s_id" +
                            " AND %3$s.%2$s_id = ?;",
                    mRelTypeTable, scanner.getTableName(), relTable);
            final String fieldName = field.getSimpleName().toString();
            scanner.instantiateAction(new OneToOneRelationInstantiateFunc()
                    .call(field.getSimpleName(), mRelType, relQuery));
            scanner.saveAction(new OneToOneRelationSaveFunc().call(field.getSimpleName(), mRelType, relTable,
                    scanner.getPrimaryKey()));
            scanner.oneToOneRelation(mRelType);
            scanner.setterAction(SQLiteColumnVisitor.canonicalSetterName(fieldName, mSetterName),
                    new Action1<ExecutableElement>() {
                        @Override
                        public void call(ExecutableElement method) {
                            env.getTree(method).accept(new SQLiteRelationSetter(
                                    env.getJavacEnv(),
                                    ClassName.get(scanner.getPackageName(), scanner.getClassName()),
                                    fieldName, env.asElement(mRelType).getSimpleName(), scanner.getPrimaryKey()
                            ));
                        }
                    });
            return mRelTypeTable;
        }

    }

    private static class OneToManyRelation implements Relation {

        private final TypeMirror mRelType;

        private final String mRelTypeTable;

        public OneToManyRelation(TypeMirror relType, String relTypeTable) {
            mRelType = relType;
            mRelTypeTable = relTypeTable;
        }

        @Override
        public String call(final SQLiteObjectScanner scanner, ProcessingEnv env, final VariableElement field) {
            final String relTable = scanner.getTableName() + "_" + mRelTypeTable;
            final String relQuery = String.format(Locale.US, "SELECT %1$s.* FROM %1$s, %3$s" +
                            " WHERE %1$s._id=%3$s.%1$s_id" +
                            " AND %3$s.%2$s_id = ?;",
                    mRelTypeTable, scanner.getTableName(), relTable);
            scanner.instantiateAction(new OneToManyRelationInstantiateFunc()
                    .call(field.getSimpleName(), mRelType, relQuery));
            scanner.saveAction(new OneToManyRelationSaveFunc()
                    .call(field.getSimpleName(), mRelType, relTable, relQuery, scanner.getPrimaryKey()));
            return mRelTypeTable;
        }

    }

    private static class UnsupportedRelation implements Relation {

        @Override
        public String call(SQLiteObjectScanner scanner, ProcessingEnv env, VariableElement field) {
            env.printMessage(Diagnostic.Kind.ERROR, field, "Unexpected relation type");
            return null;
        }

    }

    private static class OneToOneVisitor extends SimpleElementVisitor7<Relation, Void> {

        private final String mSetterName;

        public OneToOneVisitor(String setterName) {
            mSetterName = setterName;
        }

        @Override
        public Relation visitType(TypeElement e, Void aVoid) {
            final SQLiteObject annotation = e.getAnnotation(SQLiteObject.class);
            if (annotation == null) {
                return new UnsupportedRelation();
            } else {
                return new OneToOneRelation(e.asType(), annotation.value(), mSetterName);
            }
        }
    }

    private static class OneToManyVisitor extends SimpleTypeVisitor7<Relation, Void> {
        @Override
        public Relation visitDeclared(DeclaredType t, Void aVoid) {
            final SQLiteObject annotation = t.asElement().getAnnotation(SQLiteObject.class);
            if (annotation == null) {
                return new UnsupportedRelation();
            } else {
                return new OneToManyRelation(t, annotation.value());
            }
        }
    }

    private static class OneToOneRelationInstantiateFunc
            implements Func3<Name, TypeMirror, String, Action1<CodeBlock.Builder>> {

        @Override
        public Action1<CodeBlock.Builder> call(final Name fieldName, final TypeMirror relType, final String query) {
            return new Action1<CodeBlock.Builder>() {
                @Override
                public void call(CodeBlock.Builder builder) {
                    builder.addStatement("object.$L = $T.getFirst($T.rawQuery(" +
                                    "$T.class, $S, $T.getLong(cursor, $S)), null)",
                            fieldName, ClassName.get("droidkit.util", "Lists"),
                            ClassName.get("droidkit.sqlite", "SQLite"),
                            ClassName.get(relType), query,
                            ClassName.get("droidkit.util", "Cursors"), "_id");
                }
            };
        }

    }
    //endregion

    //region functions
    private static class OneToOneRelationSaveFunc
            implements Func4<Name, TypeMirror, String, Func0<String>, Action1<CodeBlock.Builder>> {

        @Override
        public Action1<CodeBlock.Builder> call(final Name fieldName, final TypeMirror relType,
                                               final String relTable, final Func0<String> primaryKey) {
            return new Action1<CodeBlock.Builder>() {
                @Override
                public void call(CodeBlock.Builder builder) {
                    builder.beginControlFlow("if(object.$L != null)", fieldName);
                    builder.addStatement("final long relId = $T.save(client, object.$L)",
                            ClassName.bestGuess(relType.toString() + "$SQLiteHelper"), fieldName);
                    builder.addStatement("client.executeInsert($S, object.$L, relId)", String.format(Locale.US,
                            "INSERT INTO %s VALUES(? , ?);", relTable), primaryKey.call());
                    builder.endControlFlow();
                }
            };
        }

    }

    private static class OneToManyRelationInstantiateFunc
            implements Func3<Name, TypeMirror, String, Action1<CodeBlock.Builder>> {

        @Override
        public Action1<CodeBlock.Builder> call(final Name fieldName, final TypeMirror relType, final String query) {
            return new Action1<CodeBlock.Builder>() {
                @Override
                public void call(CodeBlock.Builder builder) {
                    builder.addStatement("object.$L = $T.rawQuery($T.class, $S, $T.getLong(cursor, $S))",
                            fieldName, ClassName.get("droidkit.sqlite", "SQLite"),
                            ClassName.get(relType), query, ClassName.get("droidkit.util", "Cursors"), "_id");
                }
            };
        }

    }

    private static class OneToManyRelationSaveFunc
            implements Func5<Name, TypeMirror, String, String, Func0<String>, Action1<CodeBlock.Builder>> {

        @Override
        public Action1<CodeBlock.Builder> call(final Name fieldName, final TypeMirror relType,
                                               final String relTable, final String query,
                                               final Func0<String> primaryKey) {
            return new Action1<CodeBlock.Builder>() {
                @Override
                public void call(CodeBlock.Builder builder) {
                    builder.beginControlFlow("if(object.$L != null)", fieldName);
                    builder.beginControlFlow("for (final $T relEntry : object.$L)",
                            ClassName.get(relType),
                            fieldName);
                    builder.addStatement("final long relId = $T.save(client, relEntry)",
                            ClassName.bestGuess(relType.toString() + "$SQLiteHelper"));
                    builder.addStatement("client.executeInsert($S, object.$L, relId)", String.format(Locale.US,
                            "INSERT INTO %s VALUES(? , ?);", relTable), primaryKey.call());
                    builder.endControlFlow();
                    builder.addStatement("object.$L = $T.rawQuery($T.class, $S, object.$L)",
                            fieldName, ClassName.get("droidkit.sqlite", "SQLite"),
                            ClassName.get(relType), query, primaryKey.call());
                    builder.endControlFlow();
                }
            };
        }

    }
    //endregion

}
