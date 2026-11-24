import com.nexus.JobRules;
public class RuleSmoke {
    public static void main(String[] args) {
        check(JobRules.wordCount("  alpha beta\n gamma  ") == 3);
        check(JobRules.wordCount("\n\t") == 0);
        check(JobRules.sha256("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        check(JobRules.retryDelaySeconds(1) == 2);
        check(JobRules.retryDelaySeconds(5) == 32);
        System.out.println("5 Java core smoke assertions passed");
    }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
}
