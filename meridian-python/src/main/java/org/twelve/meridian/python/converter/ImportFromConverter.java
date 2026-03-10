package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.Pair;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.imexport.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Handles {@code ImportFrom}: {@code from foo import bar, baz as qux}. */
public class ImportFromConverter extends PyConverter {

    public ImportFromConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        String module = strOf(pyNode, "module");
        if (module == null) module = "__future__";
        List<Identifier> sourceIds = moduleIds(ast, module);
        List<Pair<Identifier, Identifier>> vars = new ArrayList<>();
        for (Map<String, Object> alias : listOf(pyNode, "names")) {
            String name = strOf(alias, "name");
            String asName = strOf(alias, "asname");
            Identifier imported = identifier(ast, name);
            Identifier local = identifier(ast, asName != null ? asName : name);
            vars.add(new Pair<>(imported, local));
        }
        ast.addImport(new Import(vars, sourceIds));
        return null;
    }
}
