package hex.tree.gbm;

import hex.ModelBuilder;
import hex.genmodel.algos.tree.SharedTreeSubgraph;
import hex.tree.SharedTreeModel;
import hex.tree.drf.DRFModel;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import water.Scope;
import water.TestUtil;
import water.fvec.Frame;
import water.fvec.TestFrameBuilder;
import water.fvec.Vec;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class SharedTreeTest extends TestUtil  {

  @BeforeClass
  public static void stall() { 
    stall_till_cloudsize(1);
  }

  @Parameterized.Parameters(name = "{index}: gbm({0})")
  public static Iterable<SharedTreeModel.SharedTreeParameters> data() {
    // 1. GBM
    GBMModel.GBMParameters gbmParams = new GBMModel.GBMParameters();
    gbmParams._learn_rate = 1;
    // 2. DRF
    DRFModel.DRFParameters drfParams = new DRFModel.DRFParameters();
    return Arrays.asList(gbmParams, drfParams);
  }

  @Parameterized.Parameter
  public SharedTreeModel.SharedTreeParameters parms;

  @Test
  public void testNAPredictor_cat() {
    checkNAPredictor(new TestFrameBuilder()
            .withVecTypes(Vec.T_CAT, Vec.T_CAT)
            .withDataForCol(0, ar(null, "V", null, "V", null, "V"))
    );
  }

  @Test
  public void testNAPredictor_num() {
    checkNAPredictor(new TestFrameBuilder()
            .withVecTypes(Vec.T_NUM, Vec.T_CAT)
            .withDataForCol(0, ard(Double.NaN, 1, Double.NaN, 1, Double.NaN, 1))
    );
  }

  private void checkNAPredictor(TestFrameBuilder fb) {
    Scope.enter();
    try {
      final Frame frame = fb
              .withColNames("F", "Response")
              .withDataForCol(1, ar("A", "B", "A", "B", "A", "B"))
              .build();

      parms._train = frame._key;
      parms._response_column = "Response";
      parms._ntrees = 1;
      parms._ignore_const_cols = true; // default but to make sure and illustrate the point
      parms._min_rows = 1;

      SharedTreeModel model = (SharedTreeModel) ModelBuilder.make(parms).trainModel().get();
      Scope.track_generic(model);

      // We should have a perfect model
      assertEquals(0, model.classification_error(), 0);

      // Check that we predict perfectly
      Frame test = Scope.track(frame.subframe(new String[]{"F"}));
      Frame scored = Scope.track(model.score(test));
      assertCatVecEquals(frame.vec("Response"), scored.vec("predict"));

      // Tree should split on NAs
      SharedTreeSubgraph tree0 = model.getSharedTreeSubgraph(0, 0);
      assertEquals(3, tree0.nodesArray.size()); // this implies depth 1
      assertTrue(tree0.rootNode.isNaVsRest());
    } finally {
      Scope.exit();
    }
  }


}
