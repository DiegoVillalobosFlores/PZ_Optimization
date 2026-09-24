package pzopt;

/** pzopt.ZombieLod: the controller step and the level-to-count mapping of zombieLodDynamic. */
public class ZombieLodTest {
   public static void main(String[] args) {
      double b = 1.0e9 / 240; // 4.17 ms
      float over = ZombieLod.next(1.0F, b * 1.10, b * 1.3, 240, true);
      Check.check(over < 1.0F, "a median over the budget lowers the level");
      float farOver = ZombieLod.next(1.0F, b * 2.0, b * 2.5, 240, true);
      Check.check(farOver < over, "further over the budget cuts more");
      Check.check(farOver >= 1.0F - 3 * ZombieLod.DOWN_STEP - 1.0e-6F, "at most three steps at once");
      Check.check(ZombieLod.next(0.01F, b * 2.0, b * 2.0, 240, true) == 0.0F, "never below 0");
      Check.check(ZombieLod.next(0.5F, b * 0.5, b * 3.0, 240, true) == 0.5F, "a hitch in an easy window: hold (median fine, p90 not)");
      float up = ZombieLod.next(0.5F, b * 0.5, b * 0.8, 240, true);
      Check.check(Math.abs(up - (0.5F + ZombieLod.UP_STEP)) < 1.0e-6F, "headroom climbs one small step");
      Check.check(ZombieLod.next(0.5F, b * 0.5, b * 0.8, 240, false) == 0.5F, "no climbing while held after a cut");
      Check.check(ZombieLod.next(1.0F, b * 0.5, b * 0.6, 240, true) == 1.0F, "never above 1 (stock)");
      Check.check(ZombieLod.next(0.5F, b * 0.9, b * 0.95, 240, true) == 0.5F, "between headroom and over: hold");
      Check.check(ZombieLod.next(0.5F, b * 3.0, b * 3.0, 0, false) > 0.5F, "no target: back towards stock detail");
      Check.check(ZombieLod.next(0.5F, 0.0, 0.0, 240, true) == 0.5F, "no measurement yet: hold");
      Check.check(ZombieLod.next(0.5F, 1.0e9 / 60 * 1.2, 1.0e9 / 60 * 1.3, 60, true) < 0.5F, "a 60 fps cap uses its own budget");
      Check.check(ZombieLod.next(0.5F, 1.0e9 / 60 * 0.5, 1.0e9 / 60 * 0.6, 60, true) > 0.5F, "the same step with a 60 fps cap has headroom");

      Check.check(ZombieLod.scale(1.0F, 128, 510) == 510, "level 1 = stock count");
      Check.check(ZombieLod.scale(0.0F, 128, 510) == 128, "level 0 = the floor");
      Check.check(ZombieLod.scale(0.5F, 6, 20) == 13, "linear in between");
      Check.check(ZombieLod.scale(0.0F, 30, 20) == 20, "a floor above the ceiling is clamped to it");
      Check.check(ZombieLod.scale(1.0F, 6, 12) == 12, "a lower ceiling (another mod's count) wins");
      System.out.println("ZombieLodTest ok");
   }
}
