package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import droidkit.annotation.SQLiteObject;
import droidkit.annotation.SQLiteRelation;
import droidkit.processor.ProcessingEnv;
import rx.functions.Action3;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor7;
import javax.lang.model.util.SimpleTypeVisitor7;
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
        final Relation relation = field.asType().accept(new SimpleTypeVisitor7<Relation, Void>() {
            @Override
            public Relation visitDeclared(DeclaredType t, Void aVoid) {
                if (t.getTypeArguments().isEmpty()) {
                    return t.asElement().accept(new OneToOneVisitor(), null);
                } else if (Objects.equals(List.class.getName(), t.asElement().toString())) {
                    return t.getTypeArguments().get(0).accept(new OneToManyVisitor(), null);
                }
                return new UnsupportedRelation();
            }
        }, null);
        System.out.println("SQLiteRelationVisitor.visit:46 " + relation);
        relation.call(scanner, env, field);
    }

    private interface Relation extends Action3<SQLiteObjectScanner, ProcessingEnv, VariableElement> {

    }

    private static class OneToOneRelation implements Relation {

        private final TypeMirror mRelType;

        private final String mRelTable;

        public OneToOneRelation(TypeMirror relType, String relTable) {
            mRelType = relType;
            mRelTable = relTable;
        }

        @Override
        public void call(SQLiteObjectScanner scanner, ProcessingEnv env, VariableElement field) {
            scanner.addInstantiateStatement(CodeBlock.builder()
                    .addStatement("object.$L = $T.getFirst($T.rawQuery($T.class, $S, $T.getLong(cursor, $S)))",
                            field.getSimpleName(),
                            ClassName.get("droidkit.util", "Lists"),
                            ClassName.get("droidkit.sqlite", "SQLite"),
                            ClassName.get(mRelType),
                            String.format(Locale.US, "SELECT %1$s.* FROM %1$s, %2$s_%1$s" +
                                            " WHERE %1$s._id=%2$s_%1$s.%1$s_id" +
                                            " AND %2$s_%1$s.%2$s_id = ?;",
                                    mRelTable, scanner.getTableName()),
                            ClassName.get("droidkit.util", "Cursors"), "_id")
                    .build());
        }

    }

    private static class OneToManyRelation implements Relation {

        private final TypeMirror mRelType;

        private final String mRelTable;

        public OneToManyRelation(TypeMirror relType, String relTable) {
            mRelType = relType;
            mRelTable = relTable;
        }

        @Override
        public void call(SQLiteObjectScanner scanner, ProcessingEnv env, VariableElement field) {
            scanner.addInstantiateStatement(CodeBlock.builder()
                    .addStatement("object.$L = $T.rawQuery($T.class, $S, $T.getLong(cursor, $S))",
                            field.getSimpleName(),
                            ClassName.get("droidkit.sqlite", "SQLite"),
                            ClassName.get(mRelType),
                            String.format(Locale.US, "SELECT %1$s.* FROM %1$s, %2$s_%1$s" +
                                            " WHERE %1$s._id=%2$s_%1$s.%1$s_id" +
                                            " AND %2$s_%1$s.%2$s_id = ?;",
                                    mRelTable, scanner.getTableName()),
                            ClassName.get("droidkit.util", "Cursors"), "_id")
                    .build());
        }

    }

    private static class UnsupportedRelation implements Relation {

        @Override
        public void call(SQLiteObjectScanner scanner, ProcessingEnv env, VariableElement field) {

        }

    }

    private static class OneToOneVisitor extends SimpleElementVisitor7<Relation, Void> {
        @Override
        public Relation visitType(TypeElement e, Void aVoid) {
            final SQLiteObject annotation = e.getAnnotation(SQLiteObject.class);
            if (annotation == null) {
                return new UnsupportedRelation();
            } else {
                return new OneToOneRelation(e.asType(), annotation.value());
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

}
