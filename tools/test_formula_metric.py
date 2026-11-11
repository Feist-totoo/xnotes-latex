"""Guard against an accuracy metric that accidentally erases mathematical errors."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from score_formula_structure import canonical_mathml

class FormulaMetricTest(unittest.TestCase):
    def test_spacing_is_ignored_but_mathematical_changes_are_not(self):
        cases = [
            (r"e ^ {\arctan (1+x)}", r"e^{\arctan(1+x)}", True),
            (r"\left(\begin{array}{cc}a&b\\c&d\end{array}\right)", r"(\begin{matrix}a&b\\c&d\end{matrix})", True),
            (r"x^{1 2}", r"x^{12}", True),
            (r"x+y", r"x-y", False),
            (r"x_i", r"x^i", False),
            (r"e^{\arctan(1+x)}", r"e^{avc\tan(1+x)}", False),
            (r"\vec{E}", "E", False),
            (r"\partial x", "dx", False),
            (r"\nu", "v", False),
            (r"\frac{a}{b}", r"\frac{b}{a}", False),
            (r"\begin{matrix}a&b\\c&d\end{matrix}", r"\begin{matrix}a&c\\b&d\end{matrix}", False),
            (r"\hat{x}", r"\bar{x}", False),
        ]
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder)/"source.json"; output = Path(folder)/"output.json"
            source.write_text(json.dumps([s for a,b,_ in cases for s in (a,b)]), encoding="utf-8")
            subprocess.run(["node", str(Path(__file__).parent/"formula_metrics/compare.cjs"), str(source), str(output)], check=True)
            values = [canonical_mathml(s) for s in json.loads(output.read_text(encoding="utf-8"))]
        for i,(a,b,equal) in enumerate(cases):
            self.assertIsNotNone(values[2*i], a)
            self.assertIsNotNone(values[2*i+1], b)
            self.assertEqual(equal, values[2*i] == values[2*i+1], f"{a} vs {b}")

if __name__ == "__main__": unittest.main()
