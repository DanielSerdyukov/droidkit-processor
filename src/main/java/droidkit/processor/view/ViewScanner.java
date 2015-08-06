package droidkit.processor.view;

import com.squareup.javapoet.ClassName;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import droidkit.annotation.InjectView;
import droidkit.processor.ElementScanner;
import droidkit.processor.ProcessingEnv;
import droidkit.processor.ViewInjector;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementScanner7;

/**
 * @author Daniel Serdyukov
 */
public class ViewScanner extends ElementScanner {

    private final ViewInjector mViewInjector = new ViewInjector();

    public ViewScanner(ProcessingEnv env, TypeElement originType) {
        super(env, originType);
    }

    @Override
    protected void scan() {
        getOrigin().accept(new ElementScanner7<Void, Void>() {

            @Override
            public Void visitVariable(VariableElement field, Void aVoid) {
                final InjectView annotation = field.getAnnotation(InjectView.class);
                if (annotation != null) {
                    getEnv().<JCTree.JCVariableDecl>getTree(field).mods.flags &= ~Flags.PRIVATE;
                    mViewInjector.findById("target.$L = $T.findById(root, $L)", field.getSimpleName(),
                            ClassName.get("droidkit.view", "Views"), annotation.value());
                }
                return super.visitVariable(field, aVoid);
            }

        }, null);
        mViewInjector.brewJava(getEnv(), getOrigin());
    }

}
