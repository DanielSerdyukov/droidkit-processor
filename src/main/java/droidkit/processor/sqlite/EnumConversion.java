package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import droidkit.processor.ProcessingEnv;
import rx.functions.Action1;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * @author Daniel Serdyukov
 */
class EnumConversion extends StringConversion {

    @Override
    public boolean isAcceptable(ProcessingEnv processingEnv, VariableElement field) {
        return processingEnv.isTypeOfKind(ElementKind.ENUM, field.asType());
    }

    @Override
    public String sqliteType() {
        return super.sqliteType() + " NOT NULL";
    }

    @Override
    public Action1<CodeBlock.Builder> convertToJavaType(final String fieldName, final String columnName,
                                                        final TypeMirror type) {
        return new Action1<CodeBlock.Builder>() {
            @Override
            public void call(CodeBlock.Builder builder) {
                builder.addStatement("object.$L = $T.getEnum(cursor, $S, $T.class)", fieldName,
                        ClassName.get("droidkit.util", "Cursors"), columnName,
                        ClassName.bestGuess(type.toString()));
            }
        };
    }

}
