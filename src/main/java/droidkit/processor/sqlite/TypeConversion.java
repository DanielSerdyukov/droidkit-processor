package droidkit.processor.sqlite;

import com.squareup.javapoet.CodeBlock;
import droidkit.processor.ProcessingEnv;
import rx.functions.Action1;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Arrays;
import java.util.List;

/**
 * @author Daniel Serdyukov
 */
interface TypeConversion {

    List<TypeConversion> SUPPORTED = Arrays.asList(
            new IntConversion(),
            new LongConversion(),
            new DoubleConversion(),
            new BooleanConversion(),
            new StringConversion(),
            new EnumConversion(),
            new FloatConversion(),
            new ShortConversion(),
            new BigIntegerConversion(),
            new BigDecimalConversion(),
            new DateTimeConversion(),
            new BlobConversion()
    );

    boolean isAcceptable(ProcessingEnv processingEnv, VariableElement field);

    String sqliteType();

    Action1<CodeBlock.Builder> convertToJavaType(String fieldName, String columnName, TypeMirror type);

}
