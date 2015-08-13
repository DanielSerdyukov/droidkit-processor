package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import droidkit.processor.ProcessingEnv;
import rx.functions.Action1;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * @author Daniel Serdyukov
 */
class StringConversion implements TypeConversion {

    @Override
    public boolean isAcceptable(ProcessingEnv processingEnv, VariableElement field) {
        return processingEnv.isSubtype(field.asType(), String.class);
    }

    @Override
    public String sqliteType() {
        return " TEXT";
    }

    @Override
    public Action1<CodeBlock.Builder> convertToJavaType(final String fieldName, final String columnName,
                                                        TypeMirror type) {
        return new Action1<CodeBlock.Builder>() {
            @Override
            public void call(CodeBlock.Builder builder) {
                builder.addStatement("object.$L = $T.getString(cursor, $S)", fieldName,
                        ClassName.get("droidkit.util", "Cursors"), columnName);
            }
        };
    }

}
