package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import droidkit.annotation.SQLiteColumn;
import droidkit.processor.ProcessingEnv;
import droidkit.processor.Strings;
import rx.functions.Action1;
import rx.functions.Func0;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;

/**
 * @author Daniel Serdyukov
 */
class SQLiteColumnVisitor implements FieldVisitor {

    static String canonicalSetterName(String fieldName, String setterName) {
        if (setterName.isEmpty()) {
            if ('m' == fieldName.charAt(0) && Character.isUpperCase(fieldName.charAt(1))) {
                return "set" + Strings.capitalize(fieldName.substring(1));
            } else {
                return "set" + Strings.capitalize(fieldName);
            }
        } else {
            return setterName;
        }
    }

    @Override
    public Annotation getAnnotation(ProcessingEnv processingEnv, VariableElement field) {
        return field.getAnnotation(SQLiteColumn.class);
    }

    @Override
    public void visit(final SQLiteObjectScanner scanner, final ProcessingEnv env, VariableElement field,
                      Annotation annotation) {
        final SQLiteColumn column = (SQLiteColumn) annotation;
        final String tableName = scanner.getTableName();
        final String fieldName = field.getSimpleName().toString();
        final String columnName = getColumnName(fieldName, column.value());
        final TypeConversion conversion = getTypeConversion(env, field);
        scanner.addColumnDef(columnName + conversion.sqliteType());
        scanner.putFieldToColumn(fieldName, columnName);
        scanner.setterAction(canonicalSetterName(fieldName, column.setter()), new Action1<ExecutableElement>() {
            @Override
            public void call(ExecutableElement method) {
                env.getTree(method).accept(new SQLiteColumnSetter(
                        env.getJavacEnv(),
                        ClassName.get(scanner.getPackageName(), scanner.getClassName()),
                        fieldName, columnName, scanner.getPrimaryKey()
                ));
            }
        });
        scanner.instantiateAction(conversion.convertToJavaType(fieldName, columnName, field.asType()));
        if (column.index()) {
            scanner.createIndex(new Func0<String>() {
                @Override
                public String call() {
                    return "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_" + columnName +
                            " ON " + tableName + "(" + columnName + ")";
                }
            });
        }
    }

    private TypeConversion getTypeConversion(ProcessingEnv processingEnv, VariableElement field) {
        for (final TypeConversion conversion : TypeConversion.SUPPORTED) {
            if (conversion.isAcceptable(processingEnv, field)) {
                return conversion;
            }
        }
        processingEnv.printMessage(Diagnostic.Kind.ERROR, field, "Unsupported java -> sqlite type conversion");
        throw new IllegalArgumentException("Unsupported java -> sqlite type conversion");
    }

    private String getColumnName(String fieldName, String columnName) {
        if (columnName.isEmpty()) {
            if ('m' == fieldName.charAt(0)
                    && Character.isUpperCase(fieldName.charAt(1))) {
                columnName = Strings.toUnderScope(fieldName.substring(1));
            } else {
                columnName = Strings.toUnderScope(fieldName);
            }
        }
        return columnName;
    }

}
