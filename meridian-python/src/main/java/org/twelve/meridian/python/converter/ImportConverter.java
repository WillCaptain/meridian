package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.imexport.Import;
import org.twelve.gcp.node.imexport.ImportSpecifier;

import java.util.List;
import java.util.Map;

/** Handles {@code Import}: {@code import foo, bar as baz}. */
public class ImportConverter extends PyConverter {

    public ImportConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        for (Map<String, Object> alias : listOf(pyNode, "names")) {
            String name = strOf(alias, "name");
            String asName = strOf(alias, "asname");
            List<Identifier> sourceIds = moduleIds(ast, name);
            Import imp = new Import(sourceIds);
            if (asName != null) {
                imp.specifiers().add(new ImportSpecifier(identifier(ast, name), identifier(ast, asName)));
            }
            ast.addImport(imp);
        }
        return null;
    }
}
