package biz.netcentric.cq.tools.actool.configreader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.el.ELException;

class YamlMacroElEvaluatorTest {
    private YamlMacroElEvaluator elEvaluator;
    
    @BeforeEach
    public void setUp() {
        elEvaluator = new YamlMacroElEvaluator();
    }

    @Test
    void testFunctions() {
        assertEquals("bread&amp;butter", evaluateSimpleExpression("escapeXml(\"bread&butter\")"));
        assertEquals("Test", evaluateSimpleExpression("capitalize(\"test\")"));
        assertEquals("item1,item2", evaluateSimpleExpression("join(var1, \",\")", Collections.singletonMap("var1", new Object[] {"item1", "item2"})));
    }

    @Test
    void testNonExistingFunction() {
        assertThrows(ELException.class, () -> evaluateSimpleExpression("invalid(\"test\")"));
    }

    @Test
    void testSyntaxErrpr() {
        assertThrows(ELException.class, () -> evaluateSimpleExpression("invalid(\"test\""));
    }

    private Object evaluateSimpleExpression(String expression) {
        return evaluateSimpleExpression(expression, Collections.emptyMap());
    }

    private Object evaluateSimpleExpression(String expression, Map<String, Object> variables) {
        return elEvaluator.evaluateEl("${" + expression + "}", Object.class, variables);
    }
}
