package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import droidkit.annotation.SQLiteFk;
import droidkit.annotation.SQLiteObject;
import droidkit.processor.ProcessingEnv;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Daniel Serdyukov
 */
class SQLiteFkVisitor implements FieldVisitor {

    @Override
    public Annotation getAnnotation(ProcessingEnv processingEnv, VariableElement field) {
        return field.getAnnotation(SQLiteFk.class);
    }

    @Override
    public void visit(final SQLiteObjectScanner scanner, final ProcessingEnv env, final VariableElement field,
                      final Annotation annotation) {
        if (TypeKind.LONG == field.asType().getKind()) {
            Observable.from(field.getAnnotationMirrors())
                    .flatMap(new GetAnnotationMirrorEntries())
                    .filter(new ValueFilter())
                    .flatMap(new GetAnnotationValues())
                    .flatMap(new GetRelationElement(env))
                    .flatMap(new GetRelationTableName())
                    .subscribe(new ForeignKeySupport(scanner, env, field, (SQLiteFk) annotation));
        } else {
            env.printMessage(Diagnostic.Kind.ERROR, field, "SQLiteFk must be long");
        }
    }

    //region reactive actions
    private static class GetAnnotationMirrorEntries implements Func1<AnnotationMirror, Observable<Map.Entry<
            ? extends ExecutableElement, ? extends AnnotationValue>>> {

        @Override
        public Observable<Map.Entry<? extends ExecutableElement,
                ? extends AnnotationValue>> call(AnnotationMirror mirror) {
            final Set<? extends Map.Entry<? extends ExecutableElement,
                    ? extends AnnotationValue>> entries = mirror.getElementValues().entrySet();
            return Observable.from(entries);
        }

    }

    private static class ValueFilter implements Func1<Map.Entry<? extends ExecutableElement,
            ? extends AnnotationValue>, Boolean> {

        @Override
        public Boolean call(Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry) {
            return entry.getKey().getSimpleName().toString().equals("value");
        }

    }

    private static class GetAnnotationValues implements Func1<Map.Entry<? extends ExecutableElement,
            ? extends AnnotationValue>, Observable<AnnotationValue>> {

        @Override
        public Observable<AnnotationValue> call(Map.Entry<? extends ExecutableElement,
                ? extends AnnotationValue> entry) {
            return Observable.just(entry.getValue());
        }

    }

    private static class GetRelationElement implements Func1<AnnotationValue, Observable<TypeElement>> {

        private final ProcessingEnv mEnv;

        private GetRelationElement(ProcessingEnv env) {
            mEnv = env;
        }

        @Override
        public Observable<TypeElement> call(AnnotationValue value) {
            return Observable.just(mEnv.asElement(value.getValue().toString()));
        }

    }

    private static class GetRelationTableName implements Func1<TypeElement, Observable<String>> {

        @Override
        public Observable<String> call(TypeElement typeElement) {
            final SQLiteObject annotation = typeElement.getAnnotation(SQLiteObject.class);
            if (annotation != null) {
                return Observable.just(annotation.value());
            }
            return Observable.empty();
        }

    }

    private static class ForeignKeySupport implements Action1<String> {

        private final SQLiteObjectScanner mScanner;

        private final ProcessingEnv mEnv;

        private final TypeMirror mFieldType;

        private final String mFieldName;

        private final String mSetterName;

        private final boolean mStrict;

        public ForeignKeySupport(SQLiteObjectScanner scanner, ProcessingEnv env, VariableElement field, SQLiteFk fk) {
            mScanner = scanner;
            mEnv = env;
            mFieldType = field.asType();
            mFieldName = field.getSimpleName().toString();
            mSetterName = fk.setter();
            mStrict = fk.strict();
        }

        @Override
        public void call(final String relTableName) {
            final String tableName = mScanner.getTableName();
            final String columnName = relTableName + "_id";
            mScanner.putFieldToColumn(mFieldName, columnName);
            mScanner.setterAction(SQLiteColumnVisitor.canonicalSetterName(mFieldName, mSetterName),
                    new Action1<ExecutableElement>() {
                        @Override
                        public void call(ExecutableElement method) {
                            mEnv.getTree(method).accept(new SQLiteColumnSetter(
                                    mEnv.getJavacEnv(),
                                    ClassName.get(mScanner.getPackageName(), mScanner.getClassName()),
                                    mFieldName, columnName, mScanner.getPrimaryKey()
                            ));
                        }
                    });
            mScanner.instantiateAction(new LongConversion().convertToJavaType(mFieldName, columnName, mFieldType));
            if (mStrict) {
                mScanner.addColumnDef(columnName + " INTEGER REFERENCES " + relTableName +
                        "(_id) ON DELETE CASCADE ON UPDATE CASCADE");
            } else {
                mScanner.addColumnDef(columnName + " INTEGER");
                mScanner.index(new CreateIndex(tableName, columnName));
                mScanner.trigger(new DeleteTrigger(tableName, relTableName, columnName));
                mScanner.trigger(new UpdateTrigger(tableName, relTableName, columnName));
            }
        }

    }

    private static class CreateIndex implements Func0<String> {

        private final String mTableName;

        private final String mColumnName;

        public CreateIndex(String tableName, String columnName) {
            mTableName = tableName;
            mColumnName = columnName;
        }

        @Override
        public String call() {
            return String.format(Locale.US, "CREATE INDEX IF NOT EXISTS idx_%1$s_%2$s ON %1$s(%2$s);",
                    mTableName, mColumnName);
        }

    }

    private static class DeleteTrigger implements Func0<String> {

        private final String mTableName;

        private final String mRelTableName;

        private final String mColumnName;

        public DeleteTrigger(String tableName, String relTableName, String columnName) {
            mTableName = tableName;
            mRelTableName = relTableName;
            mColumnName = columnName;
        }

        @Override
        public String call() {
            return String.format(Locale.US, "CREATE TRIGGER IF NOT EXISTS delete_%2$s_after_%1$s" +
                    " AFTER DELETE ON %1$s" +
                    " FOR EACH ROW" +
                    " BEGIN" +
                    " DELETE FROM %2$s WHERE %3$s = OLD._id;" +
                    " END", mRelTableName, mTableName, mColumnName);
        }

    }

    private static class UpdateTrigger implements Func0<String> {

        private final String mTableName;

        private final String mRelTableName;

        private final String mColumnName;

        public UpdateTrigger(String tableName, String relTableName, String columnName) {
            mTableName = tableName;
            mRelTableName = relTableName;
            mColumnName = columnName;
        }

        @Override
        public String call() {
            return String.format(Locale.US, "CREATE TRIGGER IF NOT EXISTS update_%2$s_after_%1$s" +
                    " AFTER UPDATE ON %1$s" +
                    " FOR EACH ROW" +
                    " BEGIN" +
                    " UPDATE %2$s SET %3$s = NEW._id WHERE %3$s = OLD._id;" +
                    " END", mRelTableName, mTableName, mColumnName);
        }

    }
    //endregion

}
