package droidkit.processor.sqlite;

import com.squareup.javapoet.ClassName;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Names;
import droidkit.processor.JCLiterals;
import rx.functions.Func0;

import java.util.Arrays;
import java.util.Iterator;

/**
 * @author Daniel Serdyukov
 */
class SQLiteColumnSetter extends TreeTranslator {

    private final TreeMaker mTreeMaker;

    private final Names mNames;

    private final String mPackageName;

    private final String mClassName;

    private final String mFieldName;

    private final String mColumnName;

    private final Func0<String> mPrimaryKey;

    public SQLiteColumnSetter(JavacProcessingEnvironment env, ClassName className, String fieldName,
                              String columnName, Func0<String> primaryKey) {
        mTreeMaker = TreeMaker.instance(env.getContext());
        mNames = Names.instance(env.getContext());
        mPackageName = className.packageName();
        mClassName = className.simpleName();
        mFieldName = fieldName;
        mColumnName = columnName;
        mPrimaryKey = primaryKey;
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl methodDecl) {
        super.visitMethodDef(methodDecl);
        methodDecl.body.stats = com.sun.tools.javac.util.List.<JCTree.JCStatement>of(
                mTreeMaker.Try(
                        mTreeMaker.Block(0, methodDecl.body.stats),
                        com.sun.tools.javac.util.List.<JCTree.JCCatch>nil(),
                        mTreeMaker.Block(0, com.sun.tools.javac.util.List.<JCTree.JCStatement>of(
                                mTreeMaker.Exec(mTreeMaker.Apply(
                                        com.sun.tools.javac.util.List.<JCTree.JCExpression>nil(),
                                        ident(mPackageName, mClassName, "update"),
                                        com.sun.tools.javac.util.List.of(
                                                JCLiterals.stringValue(mTreeMaker, mColumnName),
                                                thisIdent(mFieldName), thisIdent(mPrimaryKey.call())
                                        )
                                ))
                        ))
                )
        );
        this.result = methodDecl;
    }

    private JCTree.JCExpression ident(String... selectors) {
        final Iterator<String> iterator = Arrays.asList(selectors).iterator();
        JCTree.JCExpression selector = mTreeMaker.Ident(mNames.fromString(iterator.next()));
        while (iterator.hasNext()) {
            selector = mTreeMaker.Select(selector, mNames.fromString(iterator.next()));
        }
        return selector;
    }

    private JCTree.JCExpression thisIdent(String name) {
        return mTreeMaker.Select(mTreeMaker.Ident(mNames._this), mNames.fromString(name));
    }

}
