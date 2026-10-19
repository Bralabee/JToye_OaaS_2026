# The 62 mm PPDS label fixture

`capture-operators.mjs label` rasterises a real label PDF. The backend's own
renderer (`ProductLabelService`, OpenPDF) makes it, for a fictional product,
with mocked repositories: no database, no container, nothing shared.

```
cp frontend/scripts/marketing-captures/label-fixture/LabelFixtureTest.java \
   core-java/src/test/java/uk/jtoye/core/product/
LABEL_OUT=/abs/path/ppds-label.pdf ./gradlew :core-java:test --tests '*LabelFixtureTest' --offline
rm core-java/src/test/java/uk/jtoye/core/product/LabelFixtureTest.java      # never commit it there
LABEL_PDF=/abs/path/ppds-label.pdf node frontend/scripts/marketing-captures/capture-operators.mjs label
```
