package droidkit.processor.sqlite;

import droidkit.annotation.SQLitePk;
import droidkit.processor.ProcessingEnv;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;

/**
 * @author Daniel Serdyukov
 */
class SQLitePkVisitor implements FieldVisitor {

    static final String ROWID = "_id";

    static final String PRIMARY_KEY = " INTEGER PRIMARY KEY";

    @Override
    public Annotation getAnnotation(ProcessingEnv processingEnv, VariableElement field) {
        return field.getAnnotation(SQLitePk.class);
    }

    @Override
    public void visit(SQLiteObjectScanner scanner, ProcessingEnv processingEnv, VariableElement field,
                      Annotation annotation) {
        if (TypeKind.LONG == field.asType().getKind()) {
            final SQLitePk pk = (SQLitePk) annotation;
            final String fieldName = field.getSimpleName().toString();
            scanner.setPrimaryKey(fieldName);
            scanner.addColumnDef(ROWID + PRIMARY_KEY + ConflictResolution.get(pk.value()));
            scanner.putFieldToColumn(fieldName, ROWID);
            scanner.putFieldToSetter(fieldName, pk.setter());
            scanner.instantiateAction(new LongConversion().convertToJavaType(fieldName, ROWID, field.asType()));
        } else {
            processingEnv.printMessage(Diagnostic.Kind.ERROR, field, "SQLitePk must be long");
        }
    }

}
