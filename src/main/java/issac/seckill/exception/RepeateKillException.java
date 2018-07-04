package issac.seckill.exception;

/**
 * 重复秒杀异常（运行期异常）
 * author:  ywy
 * date:  2018-07-04
 * desc:
 *
 */
public class RepeateKillException extends SeckillException {
    public RepeateKillException(String message) {
        super(message);
    }

    public RepeateKillException(String message, Throwable cause) {
        super(message, cause);
    }
}