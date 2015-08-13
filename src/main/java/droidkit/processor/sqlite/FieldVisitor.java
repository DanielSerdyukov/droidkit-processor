package droidkit.processor.sqlite;

import droidkit.processor.ProcessingEnv;

import javax.lang.model.element.VariableElement;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

/**
 * @author Daniel Serdyukov
 */
interface FieldVisitor {

    List<FieldVisitor> SUPPORTED = Arrays.asList(
            new SQLitePkVisitor(),
            new SQLiteColumnVisitor(),
            new SQLiteRelationVisitor()
    );

    Annotation getAnnotation(ProcessingEnv processingEnv, VariableElement field);

    void visit(SQLiteObjectScanner scanner, ProcessingEnv processingEnv, VariableElement field,
               Annotation annotation);

}
